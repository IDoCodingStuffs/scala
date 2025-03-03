/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala.tools.nsc

import io.File

/** A class representing command line info for scalac */
class CompilerCommand(arguments: List[String], val settings: Settings) {
  def this(arguments: List[String], error: String => Unit) = this(arguments, new Settings(error))
  def this(arguments: List[String], settings: Settings, error: String => Unit) = this(arguments, settings withErrorFn error)

  type Setting = Settings#Setting

  private val processArgumentsResult =
    if (shouldProcessArguments) processArguments
    else (true, Nil)
  def ok    = processArgumentsResult._1
  def files = processArgumentsResult._2

  /** The name of the command. */
  def cmdName = "scalac"

  /** A descriptive alias for version and help messages. */
  def cmdDesc = "compiler"

  private def explainAdvanced = """
    |-- Note --
    |Boolean settings are false unless set: -Xdev -Xcheck-init:true -Xprompt:false
    |Multi-valued settings are comma-separated: -Xlint:infer-any,unused,-missing-interpolator
    |Phases are a list of names, ids, or ranges of ids: -Vprint:parser,typer,5-10 -Ylog:-4
    |Use _ to enable all: -language:_ -Vprint:_
    |
  """.stripMargin.trim

  def shortUsage = "Usage: %s <options> <source files>" format cmdName

  /** Creates a help message for a subset of options based on cond */
  def optionsMessage(cond: Setting => Boolean): String = {
    val iswarning = cond(settings.warnUnused)  // sordid check for if we're building -W warning help, to include lint and unused
    val baseList  = settings.visibleSettings.filter(cond).toList.sortBy(_.name)
    val (deprecateds, theRest) = baseList.partition(_.isDeprecated)

    def columnOneWidth(s: Setting): Int =
      if (iswarning && (s == settings.lint || s == settings.warnUnused))
        s.asInstanceOf[settings.MultiChoiceSetting[_]].choices.map(c => s"${s.name}:$c".length).max
      else
        s.helpSyntax.length
    val width               = baseList.map(columnOneWidth).max
    val columnOneFormat     = s"%-${width}s"
    def format(s: String)   = columnOneFormat.format(s)
    def layout(c1: String, c2: String) = s"${format(c1)}  ${c2}"
    def helpStr(s: Setting) = {
      val str    = layout(s.helpSyntax, s.helpDescription)
      val suffix = s.deprecationMessage match {
        case Some(msg) => "\n" + format("") + "      deprecated: " + msg
        case _         => ""
      }
      str + suffix
    }

    def appendDescriptions(sb: StringBuilder, msg: String, xs: List[Setting]): Unit =
      if (!xs.isEmpty) {
        val ss = xs.flatMap { s =>
          if (iswarning && (s == settings.lint || s == settings.warnUnused)) {
            val mcs = s.asInstanceOf[settings.MultiChoiceSetting[_]]
            mcs.choices.map(c => s"${s.name}:$c").zipAll(mcs.descriptions, "", "").map {
              case (c, d) => layout(c, d)
            }
          } else
            List(helpStr(s))
        }
        sb.append(msg)
        for (each <- ss) sb.append("  ").append(each).append("\n")
      }

    val sb = new StringBuilder()
    appendDescriptions(sb, "", theRest)
    appendDescriptions(sb, "\nDeprecated settings:\n", deprecateds)
    sb.toString
  }

  def createUsageMsg(label: String, explain: Boolean = true)(cond: Setting => Boolean): String = {
    val explained = if (explain) s"\n$explainAdvanced" else ""
    s"$shortUsage\n\n$label options:\n${optionsMessage(cond)}${explained}\n"
  }

  /** Messages explaining usage and options */
  def usageMsg  = createUsageMsg("Standard", explain = false)(_.isStandard)
  def vusageMsg = createUsageMsg("Verbose")(_.isVerbose)
  def wusageMsg = createUsageMsg("Warnings")(_.isWarning)
  def xusageMsg = createUsageMsg("Available advanced")(_.isAdvanced)
  def yusageMsg = createUsageMsg("Available private")(_.isPrivate)

  /** For info settings, compiler should just print a message and quit. */
  def shouldStopWithInfo = settings.isInfo

  def getInfoMessage(global: Global): String = {
    import settings._

    if (version)            Properties.versionFor(cmdDesc)
    else if (help)          usageMsg + global.pluginOptionsHelp
    else if (Vhelp)         vusageMsg
    else if (Whelp)         wusageMsg
    else if (Xhelp)         xusageMsg
    else if (Yhelp)         yusageMsg
    else if (showPlugins)   global.pluginDescriptions
    else if (showPhases)    global.phaseDescriptions + (
      if (debug) "\n" + global.phaseFlagDescriptions else ""
    )
    else if (genPhaseGraph.isSetByUser) {
      val components = global.phaseNames // global.phaseDescriptors // one initializes
      s"Phase graph of ${components.size} components output to ${genPhaseGraph.value}*.dot."
    }
    else allSettings.filter(_.isHelping).map(_.help).mkString("\n\n")
  }

  /**
   * Expands all arguments starting with @ to the contents of the
   * file named like each argument.
   */
  def expandArg(arg: String): List[String] = {
    def stripComment(s: String) = s takeWhile (_ != '#')
    val file = File(arg stripPrefix "@")
    if (!file.exists)
      throw new java.io.FileNotFoundException("argument file %s could not be found" format file.name)

    settings splitParams (file.lines() map stripComment mkString " ")
  }

  // override this if you don't want arguments processed here
  def shouldProcessArguments: Boolean = true

  def processArguments: (Boolean, List[String]) = {
    // expand out @filename to the contents of that filename
    val expandedArguments = arguments flatMap {
      case x if x startsWith "@"  => expandArg(x)
      case x                      => List(x)
    }

    settings.processArguments(expandedArguments, processAll = true)
  }
}
