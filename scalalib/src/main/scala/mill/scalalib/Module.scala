package mill
package scalalib

import ammonite.ops._
import coursier.{Cache, MavenRepository, Repository}
import mill.define.{Cross, Task}
import mill.define.Task.TaskModule
import mill.eval.{PathRef, Result}
import mill.modules.Jvm
import mill.modules.Jvm.{createAssembly, createJar, interactiveSubprocess, subprocess}
import Lib._
import mill.define.Cross.Resolver
import mill.util.Loose.OSet
import sbt.testing.Status

/**
  * Core configuration required to compile a single Scala compilation target
  */
trait Module extends mill.Module with TaskModule { outer =>
  def defaultCommandName() = "run"
  trait Tests extends TestModule{
    def scalaVersion = outer.scalaVersion()
    override def projectDeps = Seq(outer)
  }
  def scalaVersion: T[String]
  def mainClass: T[Option[String]] = None

  def scalaBinaryVersion = T{ scalaVersion().split('.').dropRight(1).mkString(".") }
  def ivyDeps = T{ OSet.empty[Dep] }
  def compileIvyDeps = T{ OSet.empty[Dep] }
  def scalacPluginIvyDeps = T{ OSet.empty[Dep] }
  def runIvyDeps = T{ OSet.empty[Dep] }

  def scalacOptions = T{ Seq.empty[String] }
  def javacOptions = T{ Seq.empty[String] }

  def repositories: Seq[Repository] = Seq(
    Cache.ivy2Local,
    MavenRepository("https://repo1.maven.org/maven2")
  )

  def projectDeps = Seq.empty[Module]
  def depClasspath = T{ OSet.empty[PathRef] }


  def upstreamRunClasspath = T{
    Task.traverse(projectDeps)(p =>
      T.task(p.runDepClasspath() ++ p.runClasspath())
    )
  }

  def upstreamCompileOutput = T{
    Task.traverse(projectDeps)(_.compile)
  }
  def upstreamCompileClasspath = T{
    externalCompileDepClasspath() ++
    upstreamCompileOutput().map(_.classes) ++
    Task.traverse(projectDeps)(_.compileDepClasspath)().flatten
  }

  def resolveDeps(deps: Task[OSet[Dep]], sources: Boolean = false) = T.task{
    resolveDependencies(
      repositories,
      scalaVersion(),
      scalaBinaryVersion(),
      deps(),
      sources
    )
  }

  def externalCompileDepClasspath: T[OSet[PathRef]] = T{
    OSet.from(Task.traverse(projectDeps)(_.externalCompileDepClasspath)().flatten) ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())}
    )()
  }

  def externalCompileDepSources: T[OSet[PathRef]] = T{
    OSet.from(Task.traverse(projectDeps)(_.externalCompileDepSources)().flatten) ++
    resolveDeps(
      T.task{ivyDeps() ++ compileIvyDeps() ++ scalaCompilerIvyDeps(scalaVersion())},
      sources = true
    )()
  }

  /**
    * Things that need to be on the classpath in order for this code to compile;
    * might be less than the runtime classpath
    */
  def compileDepClasspath: T[OSet[PathRef]] = T{
    upstreamCompileClasspath() ++
    depClasspath()
  }

  /**
    * Strange compiler-bridge jar that the Zinc incremental compile needs
    */
  def compilerBridge: T[PathRef] = T{
    val compilerBridgeKey = "MILL_COMPILER_BRIDGE_" + scalaVersion().replace('.', '_')
    val compilerBridgePath = sys.props(compilerBridgeKey)
    if (compilerBridgePath != null) PathRef(Path(compilerBridgePath), quick = true)
    else {
      val dep = compilerBridgeIvyDep(scalaVersion())
      val classpath = resolveDependencies(
        repositories,
        scalaVersion(),
        scalaBinaryVersion(),
        Seq(dep)
      )
      classpath match {
        case Result.Success(resolved) =>
          resolved.filter(_.path.ext != "pom").toSeq match {
            case Seq(single) => PathRef(single.path, quick = true)
            case Seq() => throw new Exception(dep + " resolution failed") // TODO: find out, is it possible?
            case _ => throw new Exception(dep + " resolution resulted in more than one file")
          }
        case f: Result.Failure => throw new Exception(dep + s" resolution failed.\n + ${f.msg}") // TODO: remove, resolveDependencies will take care of this.
      }
    }
  }

  def scalacPluginClasspath: T[OSet[PathRef]] =
    resolveDeps(
      T.task{scalacPluginIvyDeps()}
    )()

  /**
    * Classpath of the Scala Compiler & any compiler plugins
    */
  def scalaCompilerClasspath: T[OSet[PathRef]] = T{
    resolveDeps(
      T.task{scalaCompilerIvyDeps(scalaVersion()) ++ scalaRuntimeIvyDeps(scalaVersion())}
    )()
  }

  /**
    * Things that need to be on the classpath in order for this code to run
    */
  def runDepClasspath: T[OSet[PathRef]] = T{
    OSet.from(upstreamRunClasspath().flatten) ++
    depClasspath() ++
    resolveDeps(
      T.task{ivyDeps() ++ runIvyDeps() ++ scalaRuntimeIvyDeps(scalaVersion())}
    )()
  }

  def prependShellScript: T[String] = T{ "" }

  def sources = T.input{ OSet(PathRef(basePath / 'src)) }
  def resources = T.input{ OSet(PathRef(basePath / 'resources)) }
  def generatedSources = T { OSet.empty[PathRef] }
  def allSources = T{ sources() ++ generatedSources() }
  def compile: T[CompilationResult] = T.persistent{
    compileScala(
      ZincWorker(),
      scalaVersion(),
      allSources().map(_.path),
      compileDepClasspath().map(_.path),
      scalaCompilerClasspath().map(_.path),
      scalacPluginClasspath().map(_.path),
      compilerBridge().path,
      scalacOptions(),
      scalacPluginClasspath().map(_.path),
      javacOptions(),
      upstreamCompileOutput()
    )
  }
  def runClasspath = T{
    runDepClasspath() ++ resources() ++ Seq(compile().classes)
  }

  def assembly = T{
    createAssembly(
      runClasspath().map(_.path).filter(exists),
      mainClass(),
      prependShellScript = prependShellScript()
    )
  }

  def localClasspath = T{ resources() ++ Seq(compile().classes) }

  def jar = T{
    createJar(
      localClasspath().map(_.path).filter(exists),
      mainClass()
    )
  }

  def docsJar = T {
    val outDir = T.ctx().dest

    val javadocDir = outDir / 'javadoc
    mkdir(javadocDir)

    val options = {

      val files = for{
        ref <- sources()
        p <- ls.rec(ref.path)
        if p.isFile
      } yield p.toNIO.toString
      files ++ Seq("-d", javadocDir.toNIO.toString, "-usejavacp")
    }

    subprocess(
      "scala.tools.nsc.ScalaDoc",
      compileDepClasspath().filter(_.path.ext != "pom").map(_.path),
      options = options.toSeq
    )

    createJar(OSet(javadocDir))(outDir / "javadoc.jar")
  }

  def sourcesJar = T {
    createJar((sources() ++ resources()).map(_.path).filter(exists))(T.ctx().dest / "sources.jar")
  }

  def forkArgs = T{ Seq.empty[String] }

  def run(args: String*) = T.command{
    subprocess(
      mainClass().getOrElse(throw new RuntimeException("No mainClass provided!")),
      runClasspath().map(_.path),
      forkArgs(),
      args,
      workingDir = ammonite.ops.pwd)
  }

  def runMain(mainClass: String, args: String*) = T.command{
    subprocess(
      mainClass,
      runClasspath().map(_.path),
      forkArgs(),
      args,
      workingDir = ammonite.ops.pwd
    )
  }

  def console() = T.command{
    interactiveSubprocess(
      mainClass = "scala.tools.nsc.MainGenericRunner",
      classPath = runClasspath().map(_.path),
      options = Seq("-usejavacp")
    )
  }

  // publish artifact with name "mill_2.12.4" instead of "mill_2.12"
  def crossFullScalaVersion: T[Boolean] = false

  def artifactName: T[String] = basePath.last.toString
  def artifactScalaVersion: T[String] = T {
    if (crossFullScalaVersion()) scalaVersion()
    else scalaBinaryVersion()
  }

  def artifactId: T[String] = T { s"${artifactName()}_${artifactScalaVersion()}" }

}


object TestModule{
  def handleResults(doneMsg: String, results: Seq[TestRunner.Result]) = {
    if (results.count(Set(Status.Error, Status.Failure)) == 0) Result.Success((doneMsg, results))
    else {
      val grouped = results.map(_.status).groupBy(x => x).mapValues(_.length).filter(_._2 != 0).toList.sorted

      Result.Failure(grouped.map{case (k, v) => k + ": " + v}.mkString(","))
    }
  }
}
trait TestModule extends Module with TaskModule {
  override def defaultCommandName() = "test"
  def testFramework: T[String]

  def forkWorkingDir = ammonite.ops.pwd

  def forkTest(args: String*) = T.command{
    mkdir(T.ctx().dest)
    val outputPath = T.ctx().dest/"out.json"

    Jvm.subprocess(
      mainClass = "mill.scalalib.TestRunner",
      classPath = Jvm.gatherClassloaderJars(),
      jvmOptions = forkArgs(),
      options = Seq(
        testFramework(),
        runClasspath().map(_.path).mkString(" "),
        Seq(compile().classes.path).mkString(" "),
        args.mkString(" "),
        outputPath.toString,
        T.ctx().log.colored.toString
      ),
      workingDir = forkWorkingDir
    )

    val jsonOutput = upickle.json.read(outputPath.toIO)
    val (doneMsg, results) = upickle.default.readJs[(String, Seq[TestRunner.Result])](jsonOutput)
    TestModule.handleResults(doneMsg, results)

  }
  def test(args: String*) = T.command{
    val (doneMsg, results) = TestRunner(
      testFramework(),
      runClasspath().map(_.path),
      OSet(compile().classes.path),
      args
    )
    TestModule.handleResults(doneMsg, results)
  }
}