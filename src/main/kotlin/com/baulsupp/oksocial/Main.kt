package com.baulsupp.oksocial

import com.baulsupp.oksocial.Main.Companion.NAME
import com.baulsupp.oksocial.apidocs.ServiceApiDocPresenter
import com.baulsupp.oksocial.authenticator.AuthInterceptor
import com.baulsupp.oksocial.authenticator.PrintCredentials
import com.baulsupp.oksocial.commands.CommandRegistry
import com.baulsupp.oksocial.commands.MainAware
import com.baulsupp.oksocial.commands.OksocialCommand
import com.baulsupp.oksocial.commands.ShellCommand
import com.baulsupp.oksocial.completion.ArgumentCompleter
import com.baulsupp.oksocial.completion.CompletionVariableCache
import com.baulsupp.oksocial.completion.TmpCompletionVariableCache
import com.baulsupp.oksocial.completion.UrlCompleter
import com.baulsupp.oksocial.completion.UrlList
import com.baulsupp.oksocial.credentials.CredentialsStore
import com.baulsupp.oksocial.credentials.FixedTokenCredentialsStore
import com.baulsupp.oksocial.jjs.OkApiCommand
import com.baulsupp.oksocial.kotlin.await
import com.baulsupp.oksocial.okhttp.FailedResponse
import com.baulsupp.oksocial.okhttp.OkHttpResponseExtractor
import com.baulsupp.oksocial.okhttp.PotentialResponse
import com.baulsupp.oksocial.okhttp.SuccessfulResponse
import com.baulsupp.oksocial.output.DownloadHandler
import com.baulsupp.oksocial.output.OutputHandler
import com.baulsupp.oksocial.output.util.UsageException
import com.baulsupp.oksocial.util.FileContent
import com.baulsupp.oksocial.util.HeaderUtil
import com.baulsupp.oksocial.util.LoggingUtil
import io.airlift.airline.Command
import io.airlift.airline.Option
import io.airlift.airline.SingleCommand
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.internal.http.StatusLine
import java.io.File
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

@Command(name = NAME, description = "A curl for social apis.")
class Main : CommandLineClient() {
  private val logger = Logger.getLogger(Main::class.java.name)

  @Option(name = ["-X", "--request"], description = "Specify request command to use")
  var method: String? = null

  @Option(name = ["-d", "--data"], description = "HTTP POST data")
  var data: String? = null

  @Option(name = ["-H", "--header"], description = "Custom header to pass to server")
  var headers: java.util.List<String>? = null

  @Option(name = ["--no-follow"], description = "Follow redirects")
  var dontFollowRedirects = false

  @Option(name = ["-e", "--referer"], description = "Referer URL")
  var referer: String? = null

  @Option(name = ["-o", "--output"], description = "Output file/directory")
  var outputDirectory: File? = null

  @Option(name = ["--authorize"], description = "Authorize API")
  var authorize: Boolean = false

  @Option(name = ["--renew"], description = "Renew API Authorization")
  var renew: Boolean = false

  @Option(name = ["--token"], description = "Use existing Token for authorization")
  var token: String? = null

  @Option(name = ["--show-credentials"], description = "Show Credentials")
  var showCredentials = false

  @Option(name = ["--alias-names"], description = "Show Alias Names")
  var aliasNames = false

  @Option(name = ["--serviceNames"], description = "Service Names")
  var serviceNames = false

  @Option(name = ["--urlCompletion"], description = "URL Completion")
  var urlComplete: Boolean = false

  @Option(name = ["--apidoc"], description = "API Documentation")
  var apiDoc: Boolean = false

  var commandName = System.getProperty("command.name", "oksocial")!!

  var completionFile: String? = System.getenv("COMPLETION_FILE")

  var commandRegistry = CommandRegistry()

  var completionVariableCache: CompletionVariableCache? = null

  fun run(): Int {
    if (sslDebug) {
      System.setProperty("javax.net.debug", "ssl,handshake")
    }

    LoggingUtil.configureLogging(debug, showHttp2Frames)

    if (outputHandler == null) {
      outputHandler = buildHandler()
    }

    if (showHelpIfRequested()) {
      return 0
    }

    try {
      if (version) {
        outputHandler!!.info(NAME + " " + versionString())
        return 0
      }

      initialise()

      if (showCredentials) {
        runBlocking {
          PrintCredentials(client!!, credentialsStore!!, outputHandler!!, serviceInterceptor!!).showCredentials(arguments)
        }
        return 0
      }

      if (aliasNames) {
        printAliasNames()
        return 0
      }

      if (serviceNames) {
        outputHandler!!.info(serviceInterceptor!!.names().joinToString(" "))
        return 0
      }

      if (urlComplete) {
        outputHandler!!.info(urlCompletionList())
        return 0
      }

      if (apiDoc) {
        runBlocking { showApiDocs() }
        return 0
      }

      if (authorize) {
        runBlocking { authorize() }
        return 0
      }

      if (renew) {
        runBlocking { renew() }
        return 0
      }

      return runBlocking { executeRequests(outputHandler!!) }
    } catch (e: UsageException) {
      outputHandler!!.showError("error: " + e.message, null)
      return -1
    } catch (e: Exception) {
      outputHandler!!.showError("unknown error", e)
      return -2
    } finally {
      closeClients()
    }
  }

  override fun createCredentialsStore(): CredentialsStore {
    if (token != null && !authorize) {
      return FixedTokenCredentialsStore(token!!)
    }

    return super.createCredentialsStore()
  }

  override fun createClientBuilder(): OkHttpClient.Builder {
    val builder = super.createClientBuilder()

    builder.followSslRedirects(!dontFollowRedirects)
    builder.followRedirects(!dontFollowRedirects)

    return builder
  }

  suspend fun showApiDocs() {
    val docs = ServiceApiDocPresenter(serviceInterceptor!!)

    getFullCompletionUrl()?.let { u ->
      docs.explainApi(u, outputHandler!!, client!!)
    }
  }

  fun urlCompletionList(): String {
    val command = getShellCommand()

    val commandCompletor = command.completer()
    if (commandCompletor != null) {
      val urls = runBlocking {
        commandCompletion(commandCompletor, arguments)
      }

      val prefix = arguments[arguments.size - 1]

      if (completionFile != null) {
        urls.toFile(File(completionFile), 0, prefix)
      }

      return urls.getUrls(prefix).joinToString("\n")
    }

    val completer = UrlCompleter(serviceInterceptor!!.services(), client!!, credentialsStore!!,
            completionVariableCache!!)

    val fullCompletionUrl = getFullCompletionUrl()

    // reload hack (in case changed for "" case)
    val originalCompletionUrl = arguments[arguments.size - 1]

    if (fullCompletionUrl != null) {
      val urls = runBlocking {
        completer.urlList(fullCompletionUrl)
      }

      val strip: Int = if (fullCompletionUrl != originalCompletionUrl) {
        fullCompletionUrl.length - originalCompletionUrl.length
      } else {
        0
      }

      if (completionFile != null) {
        urls.toFile(File(completionFile), strip, originalCompletionUrl)
      }

      return urls.getUrls(fullCompletionUrl).joinToString("\n") { it.substring(strip) }
    } else {
      return ""
    }
  }

  fun applyRequestFields(request: Request): Request {
    val requestBuilder = request.newBuilder()

    val headerMap = HeaderUtil.headerMap(headers?.toList()).toMutableMap()

    requestBuilder.method(getRequestMethod(), getRequestBody(headerMap))

    if (headers != null) {
      headerMap.forEach { k, v -> requestBuilder.header(k, v) }
    }
    if (referer != null) {
      requestBuilder.header("Referer", referer!!)
    }
    requestBuilder.header("User-Agent", userAgent)

    return requestBuilder.build()
  }

  suspend fun commandCompletion(urlCompleter: ArgumentCompleter, arguments: List<String>): UrlList {
    return urlCompleter.urlList(arguments[arguments.size - 1])
  }

  /*
   * The last url in arguments which should be used for completion or apidoc requests.
   * In the case of javascript command expansion, it is expanded first before
   * being returned.
   *
   * n.b. arguments may be modified by this call.
   */
  private fun getFullCompletionUrl(): String? {
    if (arguments.isEmpty()) {
      return null
    }

    var urlToComplete = arguments[arguments.size - 1]

    val command = getShellCommand()

    if (command is OkApiCommand) {
      val requests = command.buildRequests(client!!, arguments)

      if (requests.isNotEmpty()) {
        val newUrl = requests[0].url()

        // support "" -> http://api.test.com
        if (urlToComplete.isEmpty() && newUrl.encodedPath() == "/") {
          urlToComplete = "/"
          arguments.removeAt(arguments.size - 1)
          arguments.add(urlToComplete)
        }

        val newUrlCompletion = newUrl.toString()

        if (newUrlCompletion.endsWith(urlToComplete)) {
          return newUrlCompletion
        }
      }
    } else if (UrlCompleter.isPossibleAddress(urlToComplete)) {
      return urlToComplete
    }

    return null
  }

  override fun initialise() {
    super.initialise()

    if (completionVariableCache == null) {
      completionVariableCache = TmpCompletionVariableCache()
    }
  }

  suspend fun executeRequests(outputHandler: OutputHandler<Response>): Int {
    val command = getShellCommand()

    val requests = command.buildRequests(client!!, arguments).map(this::applyRequestFields)

    if (!command.handlesRequests()) {
      if (requests.isEmpty()) {
        throw UsageException("no urls specified")
      }

      val responses = enqueueRequests(requests, client!!)
      val failed = processResponses(outputHandler, responses)
      return if (failed) -5 else 0
    }

    return 0
  }

  fun processResponses(outputHandler: OutputHandler<Response>,
                               responses: List<PotentialResponse>): Boolean {
    var failed = false
    for (response in responses) {
      when (response) {
        is SuccessfulResponse -> {
          showOutput(outputHandler, response.response)
          response.response.close()
        }
        is FailedResponse -> {
          outputHandler.showError("request failed", response.exception)
          failed = true
        }
      }
    }
    return failed
  }

  fun showOutput(outputHandler: OutputHandler<Response>, response: Response) {
    if (showHeaders) {
      outputHandler.info(StatusLine.get(response).toString())
      val headers = response.headers()
      var i = 0
      val size = headers.size()
      while (i < size) {
        outputHandler.info(headers.name(i) + ": " + headers.value(i))
        i++
      }
      outputHandler.info("")
    } else if (!response.isSuccessful) {
      outputHandler.showError(StatusLine.get(response).toString(), null)
    }

    outputHandler.showOutput(response)
  }

  private suspend fun enqueueRequests(requests: List<Request>, client: OkHttpClient): List<PotentialResponse> {
    val responses = mutableListOf<PotentialResponse>()

    for (request in requests) {
      logger.log(Level.FINE, "url " + request.url())

      if (requests.size > 1 && !debug) {
        System.err.println(request.url())
      }

      responses.add(makeRequest(client, request))
    }
    return responses
  }

  private fun getShellCommand(): ShellCommand {
    var shellCommand = commandRegistry.getCommandByName(commandName)

    if (shellCommand == null) {
      shellCommand = OksocialCommand()
    }

    if (shellCommand is MainAware) {
      (shellCommand as MainAware).setMain(this)
    }

    return shellCommand
  }

  private fun printAliasNames() {
    val names = commandRegistry.names().sorted()

    names.forEach({ outputHandler!!.info(it) })
  }

  private suspend fun makeRequest(client: OkHttpClient, request: Request): PotentialResponse {
    logger.log(Level.FINE, "Request " + request)

    return try {
      SuccessfulResponse(client.newCall(request).await())
    } catch (ioe: IOException) {
      FailedResponse(ioe)
    }
  }

  suspend fun authorize() {
    authorisation!!.authorize(findAuthInterceptor(), token, arguments)
  }

  suspend fun renew() {
    authorisation!!.renew(findAuthInterceptor())
  }

  private fun findAuthInterceptor(): AuthInterceptor<*>? {
    val command = getShellCommand()

    val authenticator = command.authenticator()
    var auth: AuthInterceptor<*>? = null

    if (authenticator != null) {
      auth = serviceInterceptor!!.getByName(authenticator)
    }

    if (auth == null && !arguments.isEmpty()) {
      val name = arguments.removeAt(0)

      auth = serviceInterceptor!!.findAuthInterceptor(name)
    }
    return auth
  }

  private fun getRequestMethod(): String = when {
    method != null -> method!!
    data != null -> "POST"
    else -> "GET"
  }

  override fun buildHandler(): OutputHandler<Response> = when {
    outputDirectory != null -> DownloadHandler(OkHttpResponseExtractor(), outputDirectory!!)
    else -> super.buildHandler()
  }

  private fun getRequestBody(headerMap: MutableMap<String, String>): RequestBody? {
    if (data == null) {
      return null
    }

    val mimeType = headerMap.keys
            .firstOrNull { "Content-Type".equals(it, ignoreCase = true) }
            ?.let { headerMap.remove(it)!! }
            ?: "application/x-www-form-urlencoded"

    return try {
      RequestBody.create(MediaType.parse(mimeType), FileContent.readParamBytes(data!!))
    } catch (e: IOException) {
      throw UsageException(e.message!!)
    }
  }

  companion object {
    const val NAME = "oksocial"

    private fun fromArgs(vararg args: String): Main {
      return SingleCommand.singleCommand(Main::class.java).parse(*args)
    }

    @JvmStatic
    fun main(vararg args: String) {
      val result = fromArgs(*args).run()
      System.exit(result)
    }
  }
}