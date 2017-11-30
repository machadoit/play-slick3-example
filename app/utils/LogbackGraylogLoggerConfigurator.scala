package utils

import java.io.File
import java.net.URL

import ch.qos.logback.classic.{Logger, _}
import ch.qos.logback.classic.joran._
import ch.qos.logback.classic.jul.LevelChangePropagator
import ch.qos.logback.core.util._
import org.graylog2.gelfclient.transport.GelfTransport
import org.graylog2.gelfclient.{GelfConfiguration, GelfTransports}
import org.slf4j.ILoggerFactory
import org.slf4j.bridge._
import org.slf4j.impl.StaticLoggerBinder
import play.api
import play.api._

/**
  * This LoggerConfigurator uses the standard file based logging of play framework and adds graylog logging on top
  */
class LogbackGraylogLoggerConfigurator extends LoggerConfigurator {

  var gelfClientAppender: GelfClientAppender = null


  def loggerFactory: ILoggerFactory = {
    StaticLoggerBinder.getSingleton.getLoggerFactory
  }

  /**
    * Initialize the Logger when there's no application ClassLoader available.
    */
  def init(rootPath: java.io.File, mode: Mode): Unit = {
    // Set the global application mode for logging
    play.api.Logger.setApplicationMode(mode)

    val properties = Map("application.home" -> rootPath.getAbsolutePath)
    val resourceName = if (mode == Mode.Dev) "logback-play-dev.xml" else "logback-play-default.xml"
    val resourceUrl = Option(this.getClass.getClassLoader.getResource(resourceName))
    configure(properties, resourceUrl)
  }

  def configure(env: Environment): Unit = {
    configure(env, Configuration.empty, Map.empty)
  }

  def configure(env: Environment, configuration: Configuration, optionalProperties: Map[String, String]): Unit = {
    // Get an explicitly configured resource URL
    // Fallback to a file in the conf directory if the resource wasn't found on the classpath
    def explicitResourceUrl = sys.props.get("logger.resource").map { r =>
      env.resource(r).getOrElse(new File(env.getFile("conf"), r).toURI.toURL)
    }

    // Get an explicitly configured file URL
    def explicitFileUrl = sys.props.get("logger.file").map(new File(_).toURI.toURL)

    // Get an explicitly configured URL
    def explicitUrl = sys.props.get("logger.url").map(new URL(_))

    // logback.xml is the documented method, logback-play-default.xml is the fallback that Play uses
    // if no other file is found
    def resourceUrl = env.resource("logback.xml")
      .orElse(env.resource(
        if (env.mode == Mode.Dev) "logback-play-dev.xml" else "logback-play-default.xml"
      ))

    val configUrl = explicitResourceUrl orElse explicitFileUrl orElse explicitUrl orElse resourceUrl

    val properties = LoggerConfigurator.generateProperties(env, configuration, optionalProperties)

    configure(properties, configUrl)
  }

  def configure(properties: Map[String, String], config: Option[URL]): Unit = {
    // Touching LoggerContext is not thread-safe, and so if you run several
    // application tests at the same time (spec2 / scalatest with "new WithApplication()")
    // then you will see NullPointerException as the array list loggerContextListenerList
    // is accessed concurrently from several different threads.
    //
    // The workaround is to use a synchronized block around a singleton
    // instance -- in this case, we use the StaticLoggerBinder's loggerFactory.
    loggerFactory.synchronized {
      // Redirect JUL -> SL4FJ

      // Remove existing handlers from JUL
      SLF4JBridgeHandler.removeHandlersForRootLogger()

      // Configure logback
      val ctx = loggerFactory.asInstanceOf[LoggerContext]

      val configurator = new JoranConfigurator
      configurator.setContext(ctx)

      initialyzeGraylog(ctx)

      // Set a level change propagator to minimize the overhead of JUL
      //
      // Please note that translating a java.util.logging event into SLF4J incurs the
      // cost of constructing LogRecord instance regardless of whether the SLF4J logger
      // is disabled for the given level. Consequently, j.u.l. to SLF4J translation can
      // seriously increase the cost of disabled logging statements (60 fold or 6000%
      // increase) and measurably impact the performance of enabled log statements
      // (20% overall increase). Please note that as of logback-version 0.9.25,
      // it is possible to completely eliminate the 60 fold translation overhead for
      // disabled log statements with the help of LevelChangePropagator.
      //
      // https://www.slf4j.org/api/org/slf4j/bridge/SLF4JBridgeHandler.html
      // https://logback.qos.ch/manual/configuration.html#LevelChangePropagator
      val levelChangePropagator = new LevelChangePropagator()
      levelChangePropagator.setContext(ctx)
      levelChangePropagator.setResetJUL(true)
      ctx.addListener(levelChangePropagator)
      SLF4JBridgeHandler.install()

      ctx.reset()

      // Ensure that play.Logger and play.api.Logger are ignored when detecting file name and line number for
      // logging
      val frameworkPackages = ctx.getFrameworkPackages
      frameworkPackages.add(classOf[play.Logger].getName)
      frameworkPackages.add(classOf[api.Logger].getName)

      properties.foreach { case (k, v) => ctx.putProperty(k, v) }

      config match {
        case Some(url) => configurator.doConfigure(url)
        case None =>
          System.err.println("Could not detect a logback configuration file, not configuring logback")
      }

      StatusPrinter.printIfErrorsOccured(ctx)

    }
  }

  private def initialyzeGraylog(ctx: LoggerContext) = {
    println("initialyzeGraylog")
    val rootLogger: Logger = ctx.getLogger("root")// correct name??
    println("rootlogger name: " + rootLogger.getName)
    val hostname = "graylog.example.com"
//    val hostname = "oaisjfoaisjdfoiajuzwbfz"
    val port = 12201
    val gelfConfiguration = new GelfConfiguration(hostname, port)
      .transport(GelfTransports.TCP)
      .reconnectDelay(500)
      .queueSize(512)
      .connectTimeout(1000)
      .tcpNoDelay(false)
      .sendBufferSize(1000) // causes the socket default to be used

    val transport: GelfTransport = GelfTransports.create(gelfConfiguration)

    gelfClientAppender = new GelfClientAppender(transport, hostname)

    gelfClientAppender.setContext(ctx)

    gelfClientAppender.start

    rootLogger.addAppender(gelfClientAppender)
  }

  /**
    * Shutdown the logger infrastructure.
    */
  def shutdown(): Unit = {

    val ctx = loggerFactory.asInstanceOf[LoggerContext]
    ctx.stop()

    org.slf4j.bridge.SLF4JBridgeHandler.uninstall()

    // Unset the global application mode for logging
    play.api.Logger.unsetApplicationMode()
  }

}