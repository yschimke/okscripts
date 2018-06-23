package com.baulsupp.oksocial.services.streamdata

import com.baulsupp.oksocial.authenticator.AuthInterceptor
import com.baulsupp.oksocial.authenticator.ValidatedCredentials
import com.baulsupp.oksocial.output.OutputHandler
import com.baulsupp.oksocial.secrets.Secrets
import com.baulsupp.oksocial.services.AbstractServiceDefinition
import okhttp3.Credentials
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response

class StreamdataAuthInterceptor : AuthInterceptor<String>() {

  override fun intercept(chain: Interceptor.Chain, credentials: String): Response {
    var request = chain.request()

    val signedUrl = request.url().newBuilder().addQueryParameter("X-Sd-Token", credentials).build()

    request = request.newBuilder().url(signedUrl).build()

    return chain.proceed(request)
  }

  override suspend fun authorize(client: OkHttpClient, outputHandler: OutputHandler<Response>, authArguments: List<String>): String =
    Secrets.prompt("Streamdata App Token", "streamdata.appKey", "", false)

  override val serviceDefinition = object : AbstractServiceDefinition<String>("streamdata.motwin.net", "Streamdata", "streamdata",
    "https://streamdata.io/developers/docs/", "https://portal.streamdata.io/#/home") {
    override fun parseCredentialsString(s: String): String = s

    override fun formatCredentialsString(credentials: String): String = credentials
  }

  override suspend fun validate(
    client: OkHttpClient,
    credentials: String
  ): ValidatedCredentials =
    ValidatedCredentials(credentials, null)

  override fun hosts(): Set<String> = setOf("streamdata.motwin.net", "stockmarket.streamdata.io", "streamdata.motwin.net")
}