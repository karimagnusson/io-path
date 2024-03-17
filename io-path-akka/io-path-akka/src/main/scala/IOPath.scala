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
import java.nio.file.attribute.FileTime
import java.nio.file.{
  Files,
  Paths,
  Path,
  StandardOpenOption
}

import scala.concurrent.{Future, ExecutionContext}
import scala.jdk.CollectionConverters._

import akka.actor.ActorSystem
import akka.util.ByteString
import akka.stream.scaladsl.{Source, Sink, FileIO, Framing}
import akka.stream.IOResult


object BlockingIO {

  def default(system: ActorSystem) = {
    DefaultBlockingIO(system.dispatchers.lookup(
      "pekko.actor.default-blocking-io-dispatcher"
    ))
  }

  def withContext(ec: ExecutionContext) = DefaultBlockingIO(ec)
}

trait BlockingIO {
  val ec: ExecutionContext
  def run[T](fn: => T): Future[T] = Future(fn)(ec)
}

case class DefaultBlockingIO(ec: ExecutionContext) extends BlockingIO

case class IOPathInfo(
  path: Path,
  isDir: Boolean,
  isHidden: Boolean,
  isReadable: Boolean,
  isWritable: Boolean,
  isSymbolicLink: Boolean,
  lastModified: FileTime
)


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

  val io: BlockingIO
  
  val path: Path
  def name = path.getFileName.toString
  def isFile: Boolean
  def isDir: Boolean
  def show = path.toString
  def startsWithDot = name.head == '.'
  def parent(implicit io: BlockingIO) = IODir(path.getParent)

  def delete: Future[Unit]
  def copyTo(dest: IODir): Future[Unit]
  def size(implicit ec: ExecutionContext): Future[Long]
  def isEmpty(implicit ec: ExecutionContext): Future[Boolean]
  def nonEmpty(implicit ec: ExecutionContext): Future[Boolean]

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

  def create(implicit ec: ExecutionContext): Future[IOFile] = for {
    _ <- io.run(Files.createFile(path))
  } yield this

  def size(implicit ec: ExecutionContext): Future[Long] =
    io.run(Files.size(path))

  def isEmpty(implicit ec: ExecutionContext): Future[Boolean] =
    size.map(_ == 0)

  def nonEmpty(implicit ec: ExecutionContext): Future[Boolean] =
    size.map(_ > 0)

  def delete: Future[Unit] = io.run {
    Files.deleteIfExists(path)
  }

  // read

  def readBytes: Future[Array[Byte]] = io.run {
    Files.readAllBytes(path)
  }

  def readString(implicit ec: ExecutionContext): Future[String] = for {
    bytes <- readBytes
  } yield bytes.map(_.toChar).mkString

  def readLines(implicit ec: ExecutionContext): Future[List[String]] = for {
    content <- readString
  } yield content.split("\n").toList

  // write

  def write(bytes: Array[Byte]): Future[Unit] =
    io.run(Files.write(path, bytes))

  def write(str: String): Future[Unit] =
    write(str.getBytes)

  def write(lines: Seq[String]): Future[Unit] =
    write(lines.mkString("\n").getBytes)

  // append

  def append(bytes: Array[Byte]): Future[Unit] = io.run {
    Files.write(path, bytes, StandardOpenOption.APPEND)
  }

  def append(str: String): Future[Unit] =
    append(str.getBytes)

  def append(lines: Seq[String]): Future[Unit] =
    append(("\n" + lines.mkString("\n")).getBytes)

  // copy

  def copyTo(target: IOFile): Future[Unit] = io.run {
    Files.copy(path, target.path)
  }

  def copyTo(dest: IODir): Future[Unit] = copyTo(dest.file(name))

  // rename

  def rename(target: IOFile)(implicit ec: ExecutionContext): Future[IOFile] = for {
    _ <- io.run(Files.move(path, target.path))
  } yield target

  def rename(fileName: String)(implicit ec: ExecutionContext): Future[IOFile] =
    rename(parent.file(fileName))

  def moveTo(dest: IODir)(implicit ec: ExecutionContext): Future[IOFile] =
    rename(dest.file(name))

  // stream

  def asSink: Sink[ByteString, Future[IOResult]] =
    FileIO.toPath(path)

  def stream: Source[ByteString, Future[IOResult]] = 
    FileIO.fromPath(path)

  def streamLines: Source[String, Future[IOResult]] =
    stream
      .via(Framing.delimiter(ByteString("\n"), 256, true))
      .map(_.utf8String)
}


object IODir {

  def fromPath(path: Path)(implicit io: BlockingIO) = IODir(path)
  def rel(parts: String*)(implicit io: BlockingIO) = IODir(Paths.get(IOPath.root, parts: _*))
  def get(first: String, rest: String*)(implicit io: BlockingIO) = IODir(Paths.get(first, rest: _*))

  def mkdirs(dirs: Seq[IODir])(implicit io: BlockingIO, ec: ExecutionContext): Future[Seq[IODir]] = for {
    _ <- io.run {
      dirs
        .map(_.path)
        .filterNot(Files.exists(_))
        .foreach(Files.createDirectories(_))
    }
  } yield dirs
}


case class IODir(path: Path)(implicit val io: BlockingIO) extends IOPath {

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

  def size(implicit ec: ExecutionContext): Future[Long] = io.run {
    walkDir(path).foldLeft(0L) { (acc, p) => acc + Files.size(p) }
  }

  def isEmpty(implicit ec: ExecutionContext): Future[Boolean] =
    list.map(_.isEmpty)

  def nonEmpty(implicit ec: ExecutionContext): Future[Boolean] =
    list.map(_.nonEmpty)

  def create(implicit ec: ExecutionContext): Future[IODir] = for {
    _ <- io.run(Files.createDirectories(path))
  } yield this

  def mkdir(dirName: String)(implicit ec: ExecutionContext): Future[IODir] =
    dir(dirName).create

  def mkdirs(dirNames: Seq[String])(implicit ec: ExecutionContext): Future[Seq[IODir]] =
    IODir.mkdirs(dirNames.map(dir))

  def rename(dest: IODir)(implicit ec: ExecutionContext): Future[IODir] = for {
    _ <- io.run(Files.move(path, dest.path))
  } yield dest

  def rename(dirName: String)(implicit ec: ExecutionContext): Future[IODir] =
    rename(parent.dir(dirName))

  def moveTo(dest: IODir)(implicit ec: ExecutionContext): Future[IODir] =
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

  // list

  def list: Future[List[IOPath]] = io.run {
    listDir(path).map(toIOPath)
  }

  def listFiles(implicit ec: ExecutionContext): Future[List[IOFile]] =
    list.map(IOPath.pickFiles(_))

  def listDirs(implicit ec: ExecutionContext): Future[List[IODir]] =
    list.map(IOPath.pickDirs(_))

  // walk

  def walk: Future[List[IOPath]] = io.run {
    walkDir(path).map(toIOPath)
  }

  def walkFiles(implicit ec: ExecutionContext): Future[List[IOFile]] =
    walk.map(IOPath.pickFiles(_))

  def walkDirs(implicit ec: ExecutionContext): Future[List[IODir]] =
    walk.map(IOPath.pickDirs(_))
}


















