package org.spurint.maven.plugins

import java.io.{File, FileInputStream}
import java.net.URLClassLoader
import java.util
import java.util.{Collections, Locale}
import org.apache.maven.artifact.handler.manager.ArtifactHandlerManager
import org.apache.maven.artifact.repository.layout.DefaultRepositoryLayout
import org.apache.maven.artifact.repository.{ArtifactRepository, ArtifactRepositoryPolicy, DefaultArtifactRepository}
import org.apache.maven.artifact.{Artifact, DefaultArtifact}
import org.apache.maven.execution.MavenSession
import org.apache.maven.model.Model
import org.apache.maven.model.io.xpp3.MavenXpp3Reader
import org.apache.maven.plugin.{AbstractMojo, MojoExecutionException, MojoFailureException}
import org.apache.maven.plugins.annotations._
import org.apache.maven.project.{DefaultProjectBuildingRequest, MavenProject, ProjectBuilder, ProjectBuildingException}
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate
import org.apache.maven.shared.transfer.artifact.resolve.{ArtifactResolver, ArtifactResolverException}
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

@Mojo(name = "generate-documentation", requiresDependencyResolution = ResolutionScope.COMPILE, defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
class TutMojo extends AbstractMojo {
  @Parameter(property = "sourceDirectory", defaultValue="${project.basedir}/docs-src")
  private var sourceDirectory: File = _

  @Parameter(property = "targetDirectory", defaultValue="${project.basedir}/docs")
  private var targetDirectory: File = _

  @Parameter(property = "nameFilter", defaultValue=".*\\.(md|markdown|txt|htm|html)$")
  private var nameFilter: String = _

  @Parameter(property = "scalacOptions")
  private var scalacOptions: util.List[String] = Collections.emptyList()

  @Parameter(property = "pluginJars")
  private var pluginJars: util.List[File] = Collections.emptyList()

  @Parameter(property = "tut.skip", defaultValue = "false")
  private var skip: Boolean = false

  @Parameter(property = "tut.repositoryUrl", defaultValue = "https://dl.bintray.com/tpolecat/maven")
  private var tutRepositoryUrl: String = _

  @Parameter(property = "tut.version", defaultValue = "0.6.9")
  private var tutVersion: String = _

  @Parameter(defaultValue = "${session}", required = true, readonly = true)
  private var session: MavenSession = _

  @Parameter(defaultValue = "${project}", required = true, readonly = true)
  private var project: MavenProject = _

  @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
  private var remoteRepositories: util.List[ArtifactRepository] = _

  @Component
  private var projectBuilder: ProjectBuilder = _

  @Component
  private var artifactResolver: ArtifactResolver = _

  @Component
  private var artifactHandlerManager: ArtifactHandlerManager = _

  private def tutRepository: ArtifactRepository = new DefaultArtifactRepository(
    "tut-repo",
    tutRepositoryUrl,
    new DefaultRepositoryLayout,
    new ArtifactRepositoryPolicy(false, ArtifactRepositoryPolicy.UPDATE_POLICY_NEVER, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN),
    new ArtifactRepositoryPolicy(true, ArtifactRepositoryPolicy.UPDATE_POLICY_DAILY, ArtifactRepositoryPolicy.CHECKSUM_POLICY_WARN)
  )

  private lazy val mergedRemoteRepositories: util.List[ArtifactRepository] =
    (this.remoteRepositories.asScala :+ tutRepository).asJava

  private val classpathSeparator: String =
    if (System.getProperty("os.name").toLowerCase(Locale.US).contains("windows")) ";"
    else ":"

  override def execute(): Unit = {
    if (this.skip) {
      return
    } else if (!this.sourceDirectory.exists()) {
      getLog.info(s"Directory ${this.sourceDirectory} doesn't exist; skipping execution")
      return
    }

    val allProjects = this.project +: getChildPoms(this.project.getModel).map(createProject)

    val dependencies = allProjects .flatMap(_.getArtifacts.asInstanceOf[util.Set[Artifact]].asScala)
    val resolvedDependencies = dependencies.map(resolveArtifact)

    val projectOutputPaths = allProjects
      .filter(_.getArtifact.getArtifactHandler.isAddedToClasspath)
      .flatMap(p =>
        Option(p.getCompileClasspathElements.asInstanceOf[util.List[String]])
          .fold(List.empty[File])(_.asScala.map(new File(_)).toList)
      )

    val scalaBinaryVersion = determineScalaBinaryVersion(dependencies)
      .getOrElse(throw new MojoExecutionException("Unable to determine Scala binary version"))
    val tutResolvedDependencies = resolveTutDependencies(scalaBinaryVersion)

    val scalacOptions =
      if (this.scalacOptions.isEmpty) getScalaPluginArgs.filterNot(_.startsWith("-Xplugin:"))
      else this.scalacOptions.asScala
    val pluginJars =
      if (this.pluginJars.isEmpty) getScalaPluginArgs.filter(_.startsWith("-Xplugin:"))
      else this.pluginJars.asScala.map("-Xplugin:" + _.getAbsolutePath)

    val args = List(
      this.sourceDirectory.getAbsolutePath,
      this.targetDirectory.getAbsolutePath,
      this.nameFilter,
      "-classpath",
      (resolvedDependencies ++ projectOutputPaths).distinct.map(_.getAbsolutePath).mkString(classpathSeparator)
    ) ++ scalacOptions ++ pluginJars

    runTut(tutResolvedDependencies.distinct, args)
  }

  private def runTut(tutDependencies: List[File], args: List[String]): Unit = {
    val oldClassloader = Thread.currentThread().getContextClassLoader
    try {
      val tutClassloader = new URLClassLoader(
        tutDependencies.map(_.toURI.toURL).toArray,
        ClassLoader.getSystemClassLoader.getParent
      )
      Thread.currentThread().setContextClassLoader(tutClassloader)

      val tutMainCls = tutClassloader.loadClass("tut.TutMain")
      val tutMain = tutMainCls.getMethod("main", classOf[Array[String]])
      tutMain.invoke(null, args.toArray)
    } catch {
      case NonFatal(e) => throw new MojoFailureException(e.getMessage, e)
    } finally {
      Thread.currentThread().setContextClassLoader(oldClassloader)
    }
  }

  private def resolveTutDependencies(scalaBinaryVersion: String): List[File] = {
    val tutCorePomArtifact = new DefaultArtifact(
      "org.tpolecat", s"tut-core_$scalaBinaryVersion", this.tutVersion,
      null, "pom", null, this.artifactHandlerManager.getArtifactHandler("pom"))
    val tutCorePom = resolveArtifact(tutCorePomArtifact)
    val tutCoreProject = createProject(tutCorePom)
    val tutCoreArtifact = resolveArtifact(tutCoreProject.getArtifact)
    tutCoreArtifact +: tutCoreProject.getArtifacts.asInstanceOf[util.Set[Artifact]].asScala.map(_.getFile).toList
  }

  private def getChildPoms(model: Model): List[File] = {
    val pom = model.getPomFile
    val path = pom.getParentFile
    pom +: Option(model.getModules).fold(List.empty[String])(_.asScala.toList).flatMap({ module =>
      val childPom = new File(new File(path, module), "pom.xml")
      val stream = new FileInputStream(childPom)
      try {
        val childModel = new MavenXpp3Reader().read(stream)
        childModel.setPomFile(childPom)
        childPom +: getChildPoms(childModel)
      } catch {
        case NonFatal(e) => throw new MojoExecutionException(e.getMessage, e)
      } finally {
        stream.close()
      }
    })
  }

  private def createProject(pomFile: File): MavenProject =
    try {
      val pbr = new DefaultProjectBuildingRequest(this.session.getProjectBuildingRequest)
      pbr.setResolveDependencies(true)
      this.projectBuilder.build(pomFile, pbr).getProject
    } catch {
      case e: ProjectBuildingException => throw new MojoExecutionException(e.getMessage, e)
    }

  private def resolveArtifact(artifact: Artifact): File = {
    val buildingRequest = new DefaultProjectBuildingRequest(this.session.getProjectBuildingRequest)
    buildingRequest.setRemoteRepositories(mergedRemoteRepositories)

    val coordinate = new DefaultArtifactCoordinate
    coordinate.setGroupId(artifact.getGroupId)
    coordinate.setArtifactId(artifact.getArtifactId)
    coordinate.setVersion(artifact.getVersion)
    Option(artifact.getArtifactHandler).map(_.getExtension).foreach(coordinate.setExtension)
    coordinate.setClassifier(artifact.getClassifier)

    try {
      val result = this.artifactResolver.resolveArtifact(buildingRequest, coordinate)
      Option(result.getArtifact)
        .getOrElse(throw new ArtifactResolverException("Resolver returned null artifact", new NullPointerException))
        .getFile
    } catch {
      case e: ArtifactResolverException => throw new MojoExecutionException(e.getMessage, e)
    }
  }

  private def determineScalaBinaryVersion(dependencies: List[Artifact]): Option[String] =
    dependencies
      .find(artifact => artifact.getGroupId == "org.scala-lang" && artifact.getArtifactId == "scala-library")
      .map(_.getVersion)
      .map(v => v.split("\\.").take(2).mkString("."))

  private def getScalaPluginArgs: List[String] =
    (for {
      build <- Option(this.project.getBuild)
      plugins <- Option(build.getPlugins)
      plugin <- plugins.asScala.find(p => p.getGroupId == "net.alchim31.maven" && p.getArtifactId == "scala-maven-plugin")
    } yield {
      val confs = Option(plugin.getConfiguration).toList ++
        plugin.getExecutions.asScala.filter(_.getGoals.asScala.contains("compile")).map(_.getConfiguration)

      confs.flatMap({
        case dom: org.apache.maven.shared.utils.xml.Xpp3Dom => Option(dom.getChild("args")).toList.flatMap(_.getChildren.map(_.getValue))
        case dom: org.codehaus.plexus.util.xml.Xpp3Dom => Option(dom.getChild("args")).toList.flatMap(_.getChildren.map(_.getValue))
        case _ => List.empty
      }).distinct
    }).getOrElse(List.empty)
}
