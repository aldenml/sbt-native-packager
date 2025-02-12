package com.typesafe.sbt.packager
package archetypes

import sbt.{*, given}
import sbt.Keys.{fileConverter, javaOptions, mainClass, run, sourceDirectory, streams, target}
import com.typesafe.sbt.SbtNativePackager.{Debian, Linux, Rpm, Universal}
import com.typesafe.sbt.packager.Keys.*
import com.typesafe.sbt.packager.linux.{LinuxFileMetaData, LinuxPackageMapping, LinuxPlugin, LinuxSymlink}
import com.typesafe.sbt.packager.linux.LinuxPlugin.autoImport.packageTemplateMapping
import com.typesafe.sbt.packager.debian.DebianPlugin
import com.typesafe.sbt.packager.rpm.RpmPlugin
import com.typesafe.sbt.packager.rpm.RpmPlugin.autoImport.RpmConstants
import com.typesafe.sbt.packager.archetypes.systemloader.ServerLoader
import xsbti.FileConverter

/**
  * ==Java Server App Packaging==
  *
  * Provides configuration for running an application on a server.
  *
  * @see
  *   [[http://sbt-native-packager.readthedocs.io/en/latest/archetypes/java_server/index.html]]
  */
object JavaServerAppPackaging extends AutoPlugin {
  import ServerLoader._
  import LinuxPlugin.Users

  object Names {
    val DaemonStdoutLogFileReplacement = "daemon_log_file"
  }

  override def requires = JavaAppPackaging

  object autoImport extends JavaServerAppKeys

  override def projectSettings = javaServerSettings

  val ARCHETYPE = "java_server"
  val ENV_CONFIG_REPLACEMENT = "env_config"
  val ETC_DEFAULT = "etc-default"

  /** These settings will be provided by this archetype */
  def javaServerSettings: Seq[Setting[?]] =
    linuxSettings ++ debianSettings ++ rpmSettings

  /**
    * general settings which apply to all linux server archetypes
    *
    *   - script replacements
    *   - logging directory
    *   - config directory
    */
  def linuxSettings: Seq[Setting[?]] =
    Seq(
      Linux / javaOptions := (Universal / javaOptions).value,
      // === logging directory mapping ===
      linuxPackageMappings += {
        packageTemplateMapping(defaultLinuxLogsLocation.value + "/" + (Linux / packageName).value)()
          .withUser((Linux / daemonUser).value)
          .withGroup((Linux / daemonGroup).value)
          .withPerms("755")
      },
      linuxPackageSymlinks += {
        val name = (Linux / packageName).value
        LinuxSymlink(
          defaultLinuxInstallLocation.value + "/" + name + "/logs",
          defaultLinuxLogsLocation.value + "/" + name
        )
      },
      // === etc config mapping ===
      bashScriptEnvConfigLocation := Some("/etc/default/" + (Linux / packageName).value),
      linuxStartScriptName := None,
      daemonStdoutLogFile := None
    )

  /* etcDefaultConfig is dependent on serverLoading (systemd, systemv, etc.),
   * and is therefore distro specific. As such, these settings cannot be defined
   * in the global config scope. */
  private[this] val etcDefaultConfig: Seq[Setting[?]] = Seq(
    linuxEtcDefaultTemplate := getEtcTemplateSource(sourceDirectory.value, (serverLoading ?? None).value),
    makeEtcDefault := makeEtcDefaultScript(
      packageName.value,
      (Universal / target).value,
      linuxEtcDefaultTemplate.value,
      linuxScriptReplacements.value
    ),
    linuxPackageMappings ++= etcDefaultMapping(makeEtcDefault.value, bashScriptEnvConfigLocation.value)
  )

  def debianSettings: Seq[Setting[?]] = {
    import DebianPlugin.Names.{Postinst, Postrm, Preinst, Prerm}
    inConfig(Debian)(etcDefaultConfig) ++
      inConfig(Debian)(
        Seq(
          // === Extra replacements ===
          linuxScriptReplacements ++= bashScriptEnvConfigLocation.value.map(ENV_CONFIG_REPLACEMENT -> _).toSeq,
          linuxScriptReplacements += Names.DaemonStdoutLogFileReplacement -> daemonStdoutLogFile.value.getOrElse(""),
          // === Maintainer scripts ===
          maintainerScripts := {
            val scripts = (Debian / maintainerScripts).value
            val replacements = (Debian / linuxScriptReplacements).value
            val contentOf = getScriptContent(Debian, replacements) _

            scripts ++ Map(
              Preinst -> (scripts.getOrElse(Preinst, Nil) ++ contentOf(Preinst)),
              Postinst -> (scripts.getOrElse(Postinst, Nil) ++ contentOf(Postinst)),
              Prerm -> (scripts.getOrElse(Prerm, Nil) ++ contentOf(Prerm)),
              Postrm -> (scripts.getOrElse(Postrm, Nil) ++ contentOf(Postrm))
            )
          }
        )
      ) ++ Seq(
        // === Daemon User and Group ===
        Debian / daemonUser := (Linux / daemonUser).value,
        Debian / daemonUserUid := (Linux / daemonUserUid).value,
        Debian / daemonGroup := (Linux / daemonGroup).value,
        Debian / daemonGroupGid := (Linux / daemonGroupGid).value
      )
  }

  def rpmSettings: Seq[Setting[?]] =
    inConfig(Rpm)(etcDefaultConfig) ++
      inConfig(Rpm)(
        Seq(
          // === Extra replacements ===
          linuxScriptReplacements ++= bashScriptEnvConfigLocation.value.map(ENV_CONFIG_REPLACEMENT -> _).toSeq,
          linuxScriptReplacements += Names.DaemonStdoutLogFileReplacement -> daemonStdoutLogFile.value.getOrElse(""),
          // === /var/run/app pid folder ===
          linuxPackageMappings += {
            packageTemplateMapping("/var/run/" + packageName.value)()
              .withUser(daemonUser.value)
              .withGroup(daemonGroup.value)
              .withPerms("755")
          }
        )
      ) ++ Seq(
        // === Daemon User and Group ===
        Rpm / daemonUser := (Linux / daemonUser).value,
        Rpm / daemonUserUid := (Linux / daemonUserUid).value,
        Rpm / daemonGroup := (Linux / daemonGroup).value,
        Rpm / daemonGroupGid := (Linux / daemonGroupGid).value,
        // == Maintainer scripts ===
        Rpm / maintainerScripts := rpmScriptletContents(
          rpmScriptsDirectory.value,
          (Rpm / maintainerScripts).value,
          (Rpm / linuxScriptReplacements).value
        )
      )

  /* ==========================================  */
  /* ============ Helper Methods ==============  */
  /* ==========================================  */

  /* Find the template source for the given Server loading scheme, with cascading fallback
   * If the serverLoader scheme is SystemD, then searches for files in this order:
   *
   * (assuming sourceDirectory is `src`)
   *
   * - src/templates/etc-default-systemd
   * - src/templates/etc-default
   * - Provided template
   */
  private[this] def getEtcTemplateSource(sourceDirectory: File, loader: Option[ServerLoader]): java.net.URL = {
    val defaultTemplate = getClass.getResource(ETC_DEFAULT + "-template")
    val (suffix, default) = loader
      .map {
        case Upstart => ("-upstart", defaultTemplate)
        case SystemV => ("-systemv", defaultTemplate)
        case Systemd =>
          ("-systemd", getClass.getResource(ETC_DEFAULT + "-systemd-template"))
      }
      .getOrElse(("", defaultTemplate))

    val overrides =
      List[File](sourceDirectory / "templates" / (ETC_DEFAULT + suffix), sourceDirectory / "templates" / ETC_DEFAULT)
    overrides.find(_.exists).map(_.toURI.toURL).getOrElse(default)
  }

  // Used to tell our packager to install our /etc/default/{{appName}} config file.
  protected def etcDefaultMapping(conf: Option[File], envLocation: Option[String]): Seq[LinuxPackageMapping] = {
    val mapping =
      for (
        path <- envLocation;
        c <- conf
      )
        yield LinuxPackageMapping(Seq(c -> path), LinuxFileMetaData(Users.Root, Users.Root, "644")).withConfig()

    mapping.toSeq
  }

  /**
    * Loads an available script from the native-packager source if available.
    *
    * @param config
    *   for which plugin (Debian, Rpm)
    * @param replacements
    *   for the placeholders
    * @param scriptName
    *   that should be loaded
    * @return
    *   script lines
    */
  private[this] def getScriptContent(config: Configuration, replacements: Seq[(String, String)])(
    scriptName: String
  ): Seq[String] =
    JavaServerBashScript(scriptName, ARCHETYPE, config, replacements).toSeq

  /**
    * Creates the etc-default file, which will contain the basic configuration for an app.
    *
    * @param name
    *   of the etc-default config file
    * @param tmpDir
    *   to store the resulting file in (e.g. Universal / target)
    * @param source
    *   of etc-default script
    * @param replacements
    *   for placeholders in etc-default script
    *
    * @return
    *   Some(file: File)
    */
  protected def makeEtcDefaultScript(
    name: String,
    tmpDir: File,
    source: java.net.URL,
    replacements: Seq[(String, String)]
  ): Option[File] = {
    val scriptBits = TemplateWriter.generateScript(source, replacements)
    val script = tmpDir / "tmp" / "etc" / "default" / name
    IO.write(script, scriptBits)
    Some(script)
  }

  /**
    * @param scriptDirectory
    * @param scripts
    * @param replacements
    */
  protected def rpmScriptletContents(
    scriptDirectory: File,
    scripts: Map[String, Seq[String]],
    replacements: Seq[(String, String)]
  ): Map[String, Seq[String]] = {
    import RpmConstants._
    val predefined = List(Pre, Post, Preun, Postun)
    val predefinedScripts = predefined.foldLeft(scripts) { case (scripts, script) =>
      val userDefined = Option(scriptDirectory / script) collect {
        case file if file.exists && file.isFile => file.toURI.toURL
      }
      // generate content
      val content = JavaServerBashScript(script, ARCHETYPE, Rpm, replacements, userDefined).map { script =>
        TemplateWriter generateScriptFromString (script, replacements)
      }.toSeq
      // add new content
      val newContent = scripts.getOrElse(script, Nil) ++ content.toSeq
      scripts + (script -> newContent)
    }

    // used to override template
    val rpmScripts = Option(scriptDirectory.listFiles).getOrElse(Array.empty[File])

    // remove all non files and already processed templates
    rpmScripts.filter(s => s.isFile && !predefined.contains(s.getName)).foldLeft(predefinedScripts) {
      case (scripts, scriptlet) =>
        val script = scriptlet.getName
        val existingContent = scripts.getOrElse(script, Nil)

        val loadedContent =
          JavaServerBashScript(script, ARCHETYPE, Rpm, replacements, Some(scriptlet.toURI.toURL)).map { script =>
            TemplateWriter generateScriptFromString (script, replacements)
          }.toSeq
        // add the existing and loaded content
        scripts + (script -> (existingContent ++ loadedContent))
    }
  }
}
