/*
* Copyright 2021 Kári Magnússon
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package io.github.karimagnusson.io.path

import java.io.IOException
import java.nio.file.{Files, Paths, Path, StandardOpenOption}

import scala.concurrent.{Future, ExecutionContext}
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.util.ByteString
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Compression
import org.apache.pekko.stream.connectors.file.scaladsl.Archive
import org.apache.pekko.stream.connectors.file.{TarArchiveMetadata, ArchiveMetadata}
import org.apache.pekko.stream.scaladsl.{Source, Sink, FileIO, Framing}
import org.apache.pekko.stream.IOResult
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.HttpMethods._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model._

//import io.github.karimagnusson.io.path.utils.Archive


object IOPath {
  
  lazy val root = Paths.get("").toAbsolutePath.toString

  private def toIOPath(path: Path)(implicit io: BlockingIO): Future[IOPath] = io.run {
    if (Files.isDirectory(path)) IODir(path) else IOFile(path)
  }

  def fromPath(path: Path)(implicit io: BlockingIO) =
    toIOPath(path)

  def rel(parts: String*)(implicit io: BlockingIO) =
    toIOPath(Paths.get(root, parts: _*))

  def get(first: String, rest: String*)(implicit io: BlockingIO) =
    toIOPath(Paths.get(first, rest: _*))

  def pickFiles(paths: List[IOPath]): List[IOFile] =
    paths.filter(_.isFile).map(_.asInstanceOf[IOFile])

  def pickDirs(paths: List[IOPath]): List[IODir] =
    paths.filter(_.isDir).map(_.asInstanceOf[IODir])
}


sealed trait IOPath {

  implicit val io: BlockingIO
  
  val path: Path
  def name = path.getFileName.toString
  def isFile: Boolean
  def isDir: Boolean
  def show = path.toString
  def startsWithDot = name.head == '.'
  def parent = IODir(path.getParent)

  def delete: Future[Unit]
  def copyTo(dest: IODir): Future[Unit]
  def size: Future[Long]
  def isEmpty: Future[Boolean]
  def nonEmpty: Future[Boolean]

  def exists: Future[Boolean] = io.run {
    Files.exists(path)
  }

  def info: Future[IOPathInfo] = io.run {
    IOPathInfo(
      path,
      Files.isDirectory(path),
      Files.isHidden(path),
      Files.isReadable(path),
      Files.isWritable(path),
      Files.isSymbolicLink(path),
      Files.getLastModifiedTime(path)
    )
  }

  override def toString = path.toString
}


object IOFile {

  def fromPath(path: Path)(implicit io: BlockingIO) =
    IOFile(path)

  def rel(parts: String*)(implicit io: BlockingIO) =
    IOFile(Paths.get(IOPath.root, parts: _*))

  def get(first: String, rest: String*)(implicit io: BlockingIO) =
    IOFile(Paths.get(first, rest: _*))
  
  def deleteFiles(files: Seq[IOFile])(implicit io: BlockingIO): Future[Unit] = io.run {
    files.foreach(f => Files.deleteIfExists(f.path))
  }
}


case class IOFile(path: Path)(implicit val io: BlockingIO) extends IOPath {

  implicit val system: ActorSystem = io.system
  implicit val ec: ExecutionContext = io.system.dispatcher

  def isFile = true
  def isDir = false

  def ext = name.split('.').lastOption
  def extUpper = ext.map(_.toUpperCase).getOrElse("")
  def extLower = ext.map(_.toLowerCase).getOrElse("")

  def relTo(dir: IODir) = IOFile(dir.path.relativize(path))

  private def assertFile: IOFile = {
    if (!Files.exists(path))
      throw new IOException(s"path does not exist: $path")
    if (!Files.isRegularFile(path))
      throw new IOException(s"path is not a file: $path")
    this
  } 

  def assert: Future[IOFile] = io.run(assertFile)

  def create: Future[IOFile] = for {
    _ <- io.run(Files.createFile(path))
  } yield this

  def size: Future[Long] =
    io.run(Files.size(path))

  def isEmpty: Future[Boolean] =
    size.map(_ == 0)

  def nonEmpty: Future[Boolean] =
    size.map(_ > 0)

  def delete: Future[Unit] = io.run {
    Files.deleteIfExists(path)
  }

  // read

  def readBytes: Future[Array[Byte]] = io.run {
    Files.readAllBytes(path)
  }

  def readString: Future[String] = for {
    bytes <- readBytes
  } yield bytes.map(_.toChar).mkString

  def readLines: Future[List[String]] = for {
    content <- readString
  } yield content.split("\n").toList

  // write

  def write(bytes: Array[Byte]): Future[IOFile] =
    io.run(Files.write(path, bytes)).map(_ => this)

  def write(str: String): Future[IOFile] =
    write(str.getBytes).map(_ => this)

  def write(lines: Seq[String]): Future[IOFile] =
    write(lines.mkString("\n").getBytes).map(_ => this)

  // append

  def append(bytes: Array[Byte]): Future[IOFile] =
    io.run(Files.write(path, bytes, StandardOpenOption.APPEND)).map(_ => this)

  def append(str: String): Future[IOFile] =
    append(str.getBytes).map(_ => this).map(_ => this)

  def append(lines: Seq[String]): Future[IOFile] =
    append(("\n" + lines.mkString("\n")).getBytes).map(_ => this)

  // copy

  def copyTo(target: IOFile): Future[Unit] = io.run {
    Files.copy(path, target.path)
  }

  def copyTo(dest: IODir): Future[Unit] = copyTo(dest.file(name))

  // rename

  def rename(target: IOFile): Future[IOFile] = for {
    _ <- io.run(Files.move(path, target.path))
  } yield target

  def rename(fileName: String): Future[IOFile] =
    rename(parent.file(fileName))

  def moveTo(dest: IODir): Future[IOFile] =
    rename(dest.file(name))

  // mime

  def mimeType: Future[String] = io.run {
    Files.probeContentType(path)
  }

  // gzip

  def gzip: Future[IOFile] =
    gzip(parent.file(name + ".gz"))

  def gzip(out: IOFile): Future[IOFile] =
    FileIO.fromPath(path)
      .via(Compression.gzip)
      .runWith(FileIO.toPath(out.path))
      .map(_ => out)

  
  def ungzip: Future[IOFile] =
    ungzip(parent.file(name.substring(0, name.size - 3)))

  def ungzip(out: IOFile): Future[IOFile] =
    FileIO.fromPath(path)
      .via(Compression.gunzip())
      .runWith(FileIO.toPath(out.path))
      .map(_ => out)

  // zip

  def zip(dir: IODir): Future[IOFile] = dir.listFiles.flatMap(zip)

  def zip(files: List[IOFile]): Future[IOFile] = 
    Source(files.map { file =>
      (ArchiveMetadata(file.name), FileIO.fromPath(file.path))
    })
    .via(Archive.zip())
    .runWith(FileIO.toPath(path))
    .map(_ => this)

  def unzip(dest: IODir): Future[List[IOFile]] =
    Archive
      .zipReader(path.toFile)
      .mapAsync(1) {
        case (metadata, source) =>
          val file = dest.file(metadata.name)
          source.runWith(FileIO.toPath(file.path)).map(_ => file)
      }
      .runWith(Sink.seq)
      .map(_.toList)
  
  // untar

  def untar: Future[IODir] = untar(parent)

  def untar(dest: IODir): Future[IODir] =
    FileIO.fromPath(path)
      .via(Archive.tarReader())
      .mapAsync(1) {
        case (metadata, source) =>
          if (metadata.isDirectory) {
            dest.add(IODir.get(metadata.filePath)).create
          } else {
            val file = dest.add(IOFile.get(metadata.filePath))
            for {
              _ <- file.parent.create
              _ <- source.runWith(FileIO.toPath(file.path))
            } yield ()
          }
      }
      .runWith(Sink.ignore)
      .map(_ => dest)

  def untarGz: Future[IODir] = untarGz(parent)

  def untarGz(dest: IODir): Future[IODir] =
    FileIO.fromPath(path)
      .via(Compression.gunzip().via(Archive.tarReader()))
      .mapAsync(1) {
        case (metadata, source) =>
          if (metadata.isDirectory) {
            dest.add(IODir.get(metadata.filePath)).create
          } else {
            val file = dest.add(IOFile.get(metadata.filePath))
            for {
              _ <- file.parent.create
              _ <- source.runWith(FileIO.toPath(file.path))
            } yield ()
          }
      }
      .runWith(Sink.ignore)
      .map(_ => dest)
  
  // stream

  def asSink: Sink[ByteString, Future[IOResult]] =
    FileIO.toPath(path)

  def stream: Source[ByteString, Future[IOResult]] = 
    FileIO.fromPath(path)

  def streamLines: Source[String, Future[IOResult]] =
    stream
      .via(Framing.delimiter(ByteString("\n"), 256, true))
      .map(_.utf8String)

  // download

  def download(url: String): Future[IOFile] =
    download(url, Nil)

  def download(url: String, headers: Map[String, String]): Future[IOFile] =
    download(url, headers.map(h => RawHeader(h._1, h._2)).toList)

  def download(url: String, headers: List[HttpHeader]): Future[IOFile] = {
    Http()
      .singleRequest(HttpRequest(GET, Uri(url), headers))
      .flatMap(_.entity.dataBytes.runWith(asSink))
      .map(_ => this)
  }

  // upload

  def upload(url: String): Future[String] =
    upload(url, Nil)

  def upload(url: String, headers: Map[String, String]): Future[String] =
    upload(url, headers.map(h => RawHeader(h._1, h._2)).toList)

  def upload(url: String, headers: List[HttpHeader]): Future[String] = {
    for {
      contentType <- mimeType.map { mime =>
        ContentType.parse(mime) match {
          case Left(_) => ContentTypes.NoContentType
          case Right(contentType) => contentType
        }
      }
      rsp <- Http()
        .singleRequest(HttpRequest(
          POST,
          Uri(url),
          headers,
          HttpEntity(contentType, stream)
        ))
        .flatMap(_.entity.toStrict(2.seconds))
        .map(_.data.utf8String)
    } yield rsp
  }
}


object IODir {

  def fromPath(path: Path)(implicit io: BlockingIO) = IODir(path)
  
  def rel(parts: String*)(
    implicit io: BlockingIO
  ) = get(IOPath.root, parts: _*)
  
  def get(first: String, rest: String*)(
    implicit io: BlockingIO
  ) = IODir(Paths.get(first, rest: _*))

  def mkdirs(dirs: Seq[IODir])(
    implicit io: BlockingIO,
             ec: ExecutionContext
  ): Future[Seq[IODir]] = for {
    _ <- io.run {
      dirs
        .map(_.path)
        .filterNot(Files.exists(_))
        .foreach(Files.createDirectories(_))
    }
  } yield dirs
}


case class IODir(path: Path)(implicit val io: BlockingIO) extends IOPath {

  implicit val system: ActorSystem = io.system
  implicit val ec: ExecutionContext = io.system.dispatcher

  private def listDir(p: Path): List[Path] =
    Files.list(p).iterator.asScala.toList

  private def walkDir(p: Path): List[Path] =
    Files.walk(p).iterator.asScala.toList

  private val toIOPath: Path => IOPath = { p =>
    if (Files.isDirectory(p)) IODir(p) else IOFile(p)
  }

  def isFile = false
  def isDir = true

  def relTo(other: IODir) = IODir(other.path.relativize(path))

  def add(other: IOPath): IOPath = other match {
    case p: IOFile => add(p)
    case p: IODir  => add(p)
  }

  def add(other: IOFile) = IOFile(path.resolve(other.path))

  def add(other: IODir) = IODir(path.resolve(other.path))

  def file(fileName: String) = add(IOFile.get(fileName))

  def dir(dirName: String) = add(IODir.get(dirName))

  private def assertDir: IODir = {
    if (!Files.exists(path))
      throw new IOException(s"path does not exist: $path")
    if (!Files.isDirectory(path))
      throw new IOException(s"path is not a file: $path")
    this
  } 

  def assert: Future[IODir] =
    io.run(assertDir)

  def size: Future[Long] = io.run {
    walkDir(path).foldLeft(0L) { (acc, p) => acc + Files.size(p) }
  }

  def isEmpty: Future[Boolean] =
    list.map(_.isEmpty)

  def nonEmpty: Future[Boolean] =
    list.map(_.nonEmpty)

  def create: Future[IODir] = for {
    _ <- io.run(Files.createDirectories(path))
  } yield this

  def mkdir(dirName: String): Future[IODir] =
    dir(dirName).create

  def mkdirs(dirNames: Seq[String]): Future[Seq[IODir]] =
    IODir.mkdirs(dirNames.map(dir))

  def rename(dest: IODir): Future[IODir] = for {
    _ <- io.run(Files.move(path, dest.path))
  } yield dest

  def rename(dirName: String): Future[IODir] =
    rename(parent.dir(dirName))

  def moveTo(dest: IODir): Future[IODir] =
    rename(dest.dir(name))

  def moveHere(paths: Seq[IOPath]): Future[Seq[IOPath]] = io.run {
    paths.map {
      case f: IOFile => IOFile(Files.move(f.path, path.resolve(f.name)))
      case d: IODir  => IODir(Files.move(d.path, path.resolve(d.name)))
    }
  }

  def delete: Future[Unit] = io.run {
    def loop(target: IOPath): Unit = {
      target match {
        case targetDir: IODir =>
          listDir(targetDir.path).map(toIOPath).foreach(loop)
          Files.deleteIfExists(targetDir.path)
        case targetFile: IOFile =>
          Files.deleteIfExists(targetFile.path)
      }
    }
    if (Files.exists(path))
      loop(this)
  }

  def copyTo(other: IODir): Future[Unit] = io.run {
    def loop(source: IOPath, dest: IODir): Unit = {
      source match {
        case sourceDir: IODir =>
          val nextDest = dest.dir(sourceDir.name)
          Files.createDirectory(nextDest.path)
          listDir(sourceDir.path).map(toIOPath).foreach { p =>
            loop(sourceDir.add(p), nextDest)
          }
        case sourceFile: IOFile =>
          Files.copy(sourceFile.path, dest.file(sourceFile.name).path)
      }
    }
    loop(this, other)
  }

  // tar

  private val tarMetadata: IOPath => Future[Tuple2[TarArchiveMetadata, Source[ByteString,  Any]]] = {
    case file: IOFile => io.run {
      (TarArchiveMetadata(parent.path.relativize(file.path).toString, Files.size(file.path)), FileIO.fromPath(file.path))
    }
    case dir: IODir => Future.successful {
      (TarArchiveMetadata.directory(parent.path.relativize(dir.path).toString), Source.empty)
    }
  }

  def tar: Future[IOFile] = tar(parent.file(name + ".tar"))

  def tar(out: IOFile): Future[IOFile] =
    streamWalk
      .mapAsync(1)(tarMetadata)
      .via(Archive.tar())
      .runWith(FileIO.toPath(out.path))
      .map(_ => out)
  
  def tarGz: Future[IOFile] = tarGz(parent.file(name + ".tar.gz"))

  def tarGz(out: IOFile): Future[IOFile] =
    streamWalk
      .mapAsync(1)(tarMetadata)
      .via(Archive.tar().via(Compression.gzip))
      .runWith(FileIO.toPath(out.path))
      .map(_ => out)
  
  // list

  def list: Future[List[IOPath]] = io.run {
    listDir(path).map(toIOPath)
  }

  def listFiles: Future[List[IOFile]] =
    list.map(IOPath.pickFiles(_))

  def listDirs: Future[List[IODir]] =
    list.map(IOPath.pickDirs(_))

  // walk

  def walk: Future[List[IOPath]] = io.run {
    walkDir(path).map(toIOPath)
  }

  def walkFiles: Future[List[IOFile]] =
    walk.map(IOPath.pickFiles(_))

  def walkDirs: Future[List[IODir]] =
    walk.map(IOPath.pickDirs(_))

  // stream walk

  def streamWalk: Source[IOPath, NotUsed] =
    Source.unfoldAsync(new Walker(path))(_.next).mapConcat(i => i)

  def streamWalkFiles: Source[IOFile, NotUsed] =
    streamWalk.filter(_.isFile).map(_.asInstanceOf[IOFile])

  def streamWalkDirs: Source[IODir, NotUsed] =
    streamWalk.filter(_.isDir).map(_.asInstanceOf[IODir])

  private class Walker(path: Path) {

    var iteratorOpt: Option[Iterator[Path]] = None

    def getIterator = iteratorOpt match {
      case Some(iter) => Future.successful(iter)
      case None => for {
        iter  <- io.run(Files.walk(path).iterator.asScala)
        _     <- Future.successful { iteratorOpt = Some(iter) }
      } yield iter
    }

    def next = for {
      iterator  <- getIterator
      batch     <- io.run {
        iterator.take(500).toList.map(toIOPath)
      }
      batchOpt  <- Future.successful {
        batch match {
          case Nil => None
          case paths => Some((this, paths))
        }
      }
    } yield batchOpt
  }
}









