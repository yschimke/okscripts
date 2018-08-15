package com.baulsupp.okurl.services.dropbox

import com.baulsupp.okurl.authenticator.AuthInterceptor
import com.baulsupp.okurl.authenticator.ValidatedCredentials
import com.baulsupp.okurl.authenticator.oauth2.Oauth2ServiceDefinition
import com.baulsupp.okurl.authenticator.oauth2.Oauth2Token
import com.baulsupp.okurl.credentials.TokenValue
import com.baulsupp.okurl.kotlin.queryMapValue
import com.baulsupp.okurl.kotlin.requestBuilder
import com.baulsupp.oksocial.output.OutputHandler
import com.baulsupp.okurl.secrets.Secrets
import okhttp3.FormBody
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.Response

/**
 * https://developer.dropbox.com/docs/authentication
 */
class DropboxAuthInterceptor : AuthInterceptor<Oauth2Token>() {
  override val serviceDefinition = Oauth2ServiceDefinition("api.dropboxapi.com", "Dropbox API", "dropbox",
    "https://www.dropbox.com/developers/documentation/http/documentation", "https://www.dropbox.com/developers/apps")

  override fun intercept(chain: Interceptor.Chain, credentials: Oauth2Token): Response {
    var request = chain.request()

    val token = credentials.accessToken

    val builder = request.newBuilder().addHeader("Authorization", "Bearer $token")

    if (request.method() == "GET") {
      builder.method("POST", RequestBody.create(MediaType.get("application/json"), "{}"))
    }

    request = builder.build()

    return chain.proceed(request)
  }

  override suspend fun authorize(
    client: OkHttpClient,
    outputHandler: OutputHandler<Response>,
    authArguments: List<String>
  ): Oauth2Token {
    val clientId = Secrets.prompt("Dropbox Client Id", "dropbox.clientId", "", false)
    val clientSecret = Secrets.prompt("Dropbox Client Secret", "dropbox.clientSecret", "", true)

    return DropboxAuthFlow.login(client, outputHandler, clientId, clientSecret)
  }

  override suspend fun validate(
    client: OkHttpClient,
    credentials: Oauth2Token
  ): ValidatedCredentials {
    val body = FormBody.create(MediaType.get("application/json"), "null")
    val request = requestBuilder("https://api.dropboxapi.com/2/users/get_current_account",
      TokenValue(credentials)).post(body).build()
    return ValidatedCredentials(client.queryMapValue<String>(request, "email"))
  }

  override fun hosts(): Set<String> = setOf("api.dropboxapi.com", "content.dropboxapi.com")
}