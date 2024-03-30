[![Twitter URL](https://img.shields.io/twitter/url/https/twitter.com/bukotsunikki.svg?style=social&label=Follow%20%40kuzminki_lib)](https://twitter.com/kuzminki_lib)

# io-path

io-path is a simple library for working with files and folders. It is a wrapper for java.nio.files.Path. Files and folders are handled separately with IOFile and IODir which provide convenient methods for working with files and folders.

io-path depends on Pekko. Akka is now licenced and not open for further development.

io-path-play provides a module for integration with Play framework.

This project is set up to package for Scala 2.13 and Scala 3.

Please report bugs if you find them and feel free to DM me on Twitter if you have any questions.

#### Example
```scala
import io.github.karimagnusson.io.path._

implicit val system: ActorSystem = ActorSystem()
implicit val ec: ExecutionContext = system.dispatcher
implicit val io: BlockingIO = BlockingIO(system) // Provides blocking context

val filesDir = IODir.rel("files") // Folder in project root
val textFile = filesDir.file("text.txt") // File in folder 'files'
val oldFolder = filesDir.dir("old-folder") // Folder in folder 'files'

val job = for {
  lines   <- textFile.readLines
  _       <- filesDir.file("text-copy.txt").write(lines)
  _       <- filesDir.file("profile.jpg").download("http://mysite.com/profile.jpg")
  textGz  <- textFile.gzip // gzip 'text.txt'
  _       <- textGz.upload("http://mysite.com/files") // upload 'text.txt.gz'
  _       <- oldFolder.dir("docs").copyTo(filesDir) // Copy 'docs' and it's contents to 'files'
  _       <- oldFolder.delete // Delete the folder and it's contents
  files   <- filesDir.listFiles
} yield files
```

#### BlockingIO
  
BlockingIO can be used to run blocking code.
```scala
import io.github.karimagnusson.io.path._

val io: BlockingIO = BlockingIO(system)

val res: Future[SomeType] = io.run {
  doSomeBlocking()
}
```

#### Integration with Play framework
  
Add the following to application.conf
```sbt
play.modules.enabled += "io.github.karimagnusson.io.blocking.BlockingIOModule"
```

```scala
import io.github.karimagnusson.io.path._

@Singleton
class MyController @Inject()(
  val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext,
           io: BlockingIO) extends BaseController {
```

#### IOPath

##### Static:
```scala
def fromPath(path: Path) = Future[IOPath] // IOFile or IODir
def rel(parts: String*) = Future[IOPath] // IOFile or IODir
def get(first: String, rest: String*) = Future[IOPath] // IOFile or IODir
def pickFiles(paths: List[IOPath]): List[IOFile]
def pickDirs(paths: List[IOPath]): List[IODir]
```

Methods common to `IOFile` and `IODir`
##### Methods:
```scala
val path: Path
def name: String // Name of the file or folder
def isFile: Boolean
def isDir: Boolean
def startsWithDot: Boolean
def parent: IODir
def delete: Future[Unit] // A folder will be deleted recursively
def copyTo(dest: IODir): Future[Unit] // A folder will be copied with all its contents
def size: Future[Long] // If folder, then the size of all the containing files and folders
def isEmpty: Future[Boolean]
def nonEmpty: Future[Boolean]
def exists: Future[Boolean]
def info: Future[IOPathInfo]
``` 

#### IOFile

##### Static:
```scala
def fromPath(path: Path) = IOFile
def rel(parts: String*): IOFile // Relative to working directory. Returns full path. 
def get(first: String, rest: String*): IOFile 
def deleteFiles(files: Seq[IOFile]): Future[Unit]
```

##### Methods:
```scala
def isFile: Boolean
def isDir: Boolean
def ext: Option[String]
def extUpper: String
def extLower: String
def relTo(dir: IODir): IOFile // The rest of the path relative to dir
def assert: Future[IOFile] // Assert that the file exists and that it is a file
def create: Future[IOFile]
def size: Future[Long]
def isEmpty: Future[Boolean]
def nonEmpty: Future[Boolean]
def delete: Future[Unit]
def readBytes: Future[Array[Byte]]
def readString: Future[String]
def readLines: Future[List[String]]
def write(bytes: Array[Byte]): Future[IOFile]
def write(str: String): Future[IOFile]
def write(lines: Seq[String]): Future[IOFile]
def append(bytes: Array[Byte]): Future[IOFile]
def append(str: String): Future[IOFile]
def append(lines: Seq[String]): Future[IOFile]
def copyTo(target: IOFile): Future[Unit]
def copyTo(dest: IODir): Future[Unit]
def rename(target: IOFile): Future[IOFile]
def rename(fileName: String): Future[IOFile]
def moveTo(dest: IODir): Future[IOFile]
def mimeType: Future[Option[String]] // detect mime type
def gzip: Future[IOFile] // file name + .gz
def gzip(out: IOFile): Future[IOFile]
def ungzip: Future[IOFile] // file name - .gz
def ungzip(out: IOFile): Future[IOFile]
def zip(dir: IODir): Future[IOFile] // zip all files in a folder
def zip(files: List[IOFile]): Future[IOFile] // zip a list of filles
def unzip: Future[List[IOFile]]
def unzip(dest: IODir): Future[List[IOFile]] // unzip does not support folders
def untar: Future[IODir]
def untar(dest: IODir): Future[IODir]
def untarGz: Future[IODir]
def untarGz(dest: IODir): Future[IODir]
def asSink: Sink[ByteString, Future[IOResult]]
def stream: Source[ByteString, Future[IOResult]]
def streamLines: Source[String, Future[IOResult]]
def download(url: String): Future[IOFile]
def download(url: String, headers: Map[String, String]): Future[IOFile]
def download(url: String, headers: List[HttpHeader]): Future[IOFile]
def upload(url: String): Future[String]
def upload(url: String, headers: Map[String, String]): Future[String]
def upload(url: String, headers: List[HttpHeader]): Future[String]
```

#### IODir

##### Static:
```scala
def fromPath(path: Path): IODir
def rel(parts: String*): IODir // Relative to working directory. Returns full path.
def get(first: String, rest: String*): IODir
def mkdirs(dirs: Seq[String]): Future[List[IODir]]
```

##### Methods:
```scala
def isFile: Boolean
def isDir: Boolean
def relTo(other: IODir): IODir // The rest of the path relative to other
def relTo(other: IOFile) = IOFile
def add(other: IOPath): IOPath
def add(other: IOFile): IOFile
def add(other: IODir): IODir
def file(fileName: String): IOFile
def dir(dirName: String): IODir
def assert: Future[IODir] // Assert that the folder exists and that it is a folder
def size: Future[Long] // The combined size of all the containing files and folders
def isEmpty: Future[Boolean]
def nonEmpty: Future[Boolean]
def create: Future[IODir]
def mkdir(dirName: String): Future[IODir]
def mkdirs(dirNames: Seq[String]): Future[Seq[IODir]]
def rename(dest: IODir): Future[IODir]
def rename(dirName: String): Future[IODir]
def moveTo(dest: IODir): Future[IODir]
def moveHere(paths: Seq[IOPath]): Future[Seq[IOPath]]
def delete: Future[Unit] // Delete the folder and all its contents
def copyTo(other: IODir): Future[Unit] // Copy the folder and all its contents
def tar: Future[IOFile]
def tar(out: IOFile): Future[IOFile]
def tarGz: Future[IOFile]
def tarGz(out: IOFile): Future[IOFile]
def list: Future[List[IOPath]] // List all the files and folders
def listFiles: Future[List[IOFile]]
def listDirs: Future[List[IODir]]
def walk: Future[List[IOPath]]
def walkFiles: Future[List[IOFile]]
def walkDirs: Future[List[IODir]]
def streamWalk: Source[IOPath, NotUsed]
def streamWalkFiles: Source[IOFile, NotUsed]
def streamWalkDirs: Source[IODir, NotUsed]
```







