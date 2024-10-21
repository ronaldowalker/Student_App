package dev.kwasi.echoservercomplete.network

import android.util.Log
import com.google.gson.Gson
import dev.kwasi.echoservercomplete.models.ContentModel
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.text.Charsets.UTF_8

class Client(private val messageListener: NetworkMessageInterface, studentIDSeed: String) {
    private lateinit var socketConnection: Socket
    private lateinit var inputReader: BufferedReader
    private lateinit var outputWriter: BufferedWriter
    var clientIp: String = ""

    // Encrypting and authentication-related variables
    private val hashedSeed = hashStringWithSHA256(studentIDSeed)
    private val encryptionKey = generateAESKey(hashedSeed)
    private val initializationVector = generateIV(hashedSeed)
    
    private var isFirstMessage = true
    private var isValid = true

    init {
        // Start a background thread for the client
        thread {
            // Establish a connection to the server
            socketConnection = Socket("192.168.49.1", Server.PORT)
            Log.e("Client", hashedSeed)
            inputReader = socketConnection.inputStream.bufferedReader()
            outputWriter = socketConnection.outputStream.bufferedWriter()
            clientIp = socketConnection.inetAddress.hostAddress!!

            try {
                // Send initial handshake message to the server
                val initialContent = ContentModel("Client connected", clientIp)
                sendUnencryptedMessage(initialContent)

                // Await server's response
                var serverResponse = inputReader.readLine()
                while (serverResponse == null) {
                    serverResponse = inputReader.readLine()
                    Log.e("Client", "Waiting for response from server")
                }

                var receivedContent = Gson().fromJson(serverResponse, ContentModel::class.java)
                Log.e("Client", receivedContent.message)

                // Encrypt the server's message and send a reply
                val encryptedMessage = encryptText(receivedContent.message, encryptionKey, initializationVector)
                val responseContent = ContentModel(encryptedMessage, hashedSeed)
                sendUnencryptedMessage(responseContent)

                // Validate client with server
                sendUnencryptedMessage(ContentModel(encryptText(studentIDSeed, encryptionKey, initializationVector), clientIp))
                
                // Validate server response
                serverResponse = inputReader.readLine()
                receivedContent = Gson().fromJson(serverResponse, ContentModel::class.java)
                if (decryptText(receivedContent.message, encryptionKey, initializationVector) != "VALID") {
                    isValid = false
                    messageListener.failedConnection()
                    socketConnection.close()
                }
            } catch (e: Exception) {
                Log.e("Client", "Error during client-server communication")
                e.printStackTrace()
            }

            // Continuously listen for incoming messages from the server
            while (true) {
                try {
                    val serverMessage = inputReader.readLine()
                    if (serverMessage != null) {
                        val receivedContent = Gson().fromJson(serverMessage, ContentModel::class.java)
                        
                        // Handle initial message separately
                        if (isFirstMessage) {
                            sendMessage(receivedContent)
                            isFirstMessage = false
                        } else {
                            val decryptedMessage = decryptText(receivedContent.message.reversed(), encryptionKey, initializationVector)
                            receivedContent.message = decryptedMessage
                            messageListener.onContent(receivedContent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("Client", "Error during message handling")
                    e.printStackTrace()
                    break
                }
            }
        }
    }

    // Method to send an encrypted message
    fun sendMessage(content: ContentModel) {
        thread {
            if (!socketConnection.isConnected) {
                throw Exception("Client is not connected to the server!")
            }

            content.message = encryptText(content.message, encryptionKey, initializationVector)
            val serializedContent = Gson().toJson(content)
            outputWriter.write("$serializedContent\n")
            outputWriter.flush()
        }
    }

    // Method to send unencrypted (plain) message
    private fun sendUnencryptedMessage(content: ContentModel) {
        thread {
            if (!socketConnection.isConnected) {
                throw Exception("Client is not connected to the server!")
            }

            val serializedContent = Gson().toJson(content)
            outputWriter.write("$serializedContent\n")
            outputWriter.flush()
        }
    }

    // Close the client connection
    fun closeConnection() {
        isFirstMessage = true
        socketConnection.close()
    }

    // Hashes a string using SHA-256 algorithm
    private fun hashStringWithSHA256(input: String): String {
        val algorithm = "SHA-256"
        val digest = MessageDigest.getInstance(algorithm).digest(input.toByteArray(UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    // Generate AES key from a hashed seed
    private fun generateAESKey(seed: String): SecretKeySpec {
        val first32Chars = seed.take(32)
        return SecretKeySpec(first32Chars.toByteArray(), "AES")
    }

    // Generate IV (Initialization Vector) for AES encryption from a hashed seed
    private fun generateIV(seed: String): IvParameterSpec {
        val first16Chars = seed.take(16)
        return IvParameterSpec(first16Chars.toByteArray())
    }

    // Encrypt the message using AES encryption
    @OptIn(ExperimentalEncodingApi::class)
    private fun encryptText(plainText: String, key: SecretKey, iv: IvParameterSpec): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.ENCRYPT_MODE, key, iv)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray())
        return Base64.Default.encode(encryptedBytes)
    }

    // Decrypt the message using AES decryption
    @OptIn(ExperimentalEncodingApi::class)
    private fun decryptText(encryptedText: String, key: SecretKey, iv: IvParameterSpec): String {
        val decodedBytes = Base64.Default.decode(encryptedText)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5PADDING")
        cipher.init(Cipher.DECRYPT_MODE, key, iv)
        return String(cipher.doFinal(decodedBytes))
    }
}
