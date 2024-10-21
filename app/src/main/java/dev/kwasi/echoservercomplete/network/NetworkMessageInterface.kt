package dev.kwasi.echoservercomplete.network

import dev.kwasi.echoservercomplete.models.ContentModel

/// This [NetworkMessageInterface] acts as an interface.
interface NetworkMessageInterface {
    fun onContent(content: ContentModel)
    fun failedConnection()
}