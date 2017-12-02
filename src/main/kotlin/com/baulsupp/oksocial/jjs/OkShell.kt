package com.baulsupp.oksocial.jjs

import com.baulsupp.oksocial.CommandLineClient
import com.baulsupp.oksocial.kotlin.client
import com.baulsupp.oksocial.kotlin.warmup
import com.baulsupp.oksocial.location.Location
import okhttp3.Request

class OkShell(val commandLine: CommandLineClient) {
  fun warmup(vararg urls: String) {
    client.warmup(*urls)
  }

  fun show(url: String) {
    val request = Request.Builder().url(url).build()

    val call = client.newCall(request)

    val response = call.execute()

    commandLine.outputHandler!!.showOutput(response)
  }

  fun credentials(name: String): Any? {
    val interceptor = commandLine.serviceInterceptor!!.getByName(name)

    if (interceptor != null) {
      return commandLine.credentialsStore!!.readDefaultCredentials(interceptor.serviceDefinition())
    }

    return null
  }

  fun location(): Location? = commandLine.locationSource!!.read()

  companion object {
    var instance: OkShell? = null
  }
}
