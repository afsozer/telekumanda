package com.agent.bridge.udf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

object MobilImzaManager {
    private const val ENDPOINT = "https://vatandas.uyap.gov.tr/mimzaclient/services/MImzaSigner"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val DEFAULT_READ_TIMEOUT_MS = 20_000
    private const val SIGNATURE_READ_TIMEOUT_MS = 180_000

    const val RESULT_SUCCESS = 0
    const val RESULT_PENDING = -1
    const val RESULT_WAITING = 9999
    const val RESULT_ERROR_NETWORK = -2

    // Mappings: TURKCELL -> 1, AVEA/TURKTELEKOM -> 2, VODAFONE -> 3
    fun getOperatorId(operatorName: String): Int {
        return when (operatorName.uppercase()) {
            "TURKCELL" -> 1
            "AVEA", "TURKTELEKOM", "TURK TELEKOM" -> 2
            "VODAFONE" -> 3
            else -> throw IllegalArgumentException("Bilinmeyen GSM operatörü: $operatorName")
        }
    }

    private fun xmlEscape(str: String): String {
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&apos;")
    }

    data class HashResponse(
        val apTransId: String,
        val fingerPrint: String,
        val message: String,
        val resultCode: Int
    )

    data class SignResponse(
        val apTransId: String,
        val signatureBytes: ByteArray?,
        val message: String,
        val resultCode: Int
    )

    suspend fun getHash(
        documentBytes: ByteArray,
        telNo: String,
        operatorName: String
    ): HashResponse = withContext(Dispatchers.IO) {
        val normalizedTel = normalizePhoneNumber(telNo)
        if (normalizedTel == null) {
            return@withContext HashResponse("", "", "Geçersiz telefon numarası. Lütfen 11 haneli (örn: 05xx...) olacak şekilde giriniz.", RESULT_PENDING)
        }

        val operatorId = getOperatorId(operatorName)
        val docBase64 = Base64.getEncoder().encodeToString(documentBytes)

        val requestEnvelope = buildGetHashEnvelope(docBase64, normalizedTel, operatorId)

        // Ağ/timeout hataları geçici olabileceği için getHash bir kez yeniden denenir.
        // Geçersiz telefon veya operatör gibi kullanıcı girişi hataları bu döngüye
        // gelmeden önce zaten RESULT_PENDING / exception olarak ele alınmıştır.
        val maxAttempts = 2
        var lastException: Exception? = null
        for (attempt in 1..maxAttempts) {
            try {
                val responseXml = sendSoapRequest("getHash", requestEnvelope)
                return@withContext parseHashResponse(responseXml)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Hata yutulmaz: son deneme sonrası mesajı HashResponse ile döner.
                lastException = e
            }
        }
        HashResponse("", "", lastException?.message ?: "Ağ hatası", RESULT_ERROR_NETWORK)
    }

    suspend fun getSignature(apTransId: String): SignResponse = withContext(Dispatchers.IO) {
        val requestEnvelope = buildGetSignatureEnvelope(
            dataToBeDisplayed = "Evrak Imzalama",
            apTransId = apTransId
        )

        try {
            val responseXml = sendSoapRequest(
                action = "getSignature",
                envelope = requestEnvelope,
                readTimeoutMillis = SIGNATURE_READ_TIMEOUT_MS
            )
            parseSignatureResponse(responseXml)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SignResponse(apTransId, null, e.message ?: "Network error", RESULT_ERROR_NETWORK)
        }
    }

    internal fun buildGetHashEnvelope(documentBase64: String, telNo: String, operatorId: Int): String {
        val escapedDoc = xmlEscape(documentBase64)
        val escapedTel = xmlEscape(telNo)
        return """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:cli="http://client.mimza.uyap.gov.tr">
               <soapenv:Header/>
               <soapenv:Body>
                  <cli:getHash>
                     <cli:dataToBeSigned>$escapedDoc</cli:dataToBeSigned>
                     <cli:telNo>$escapedTel</cli:telNo>
                     <cli:gsmOperator>$operatorId</cli:gsmOperator>
                  </cli:getHash>
               </soapenv:Body>
            </soapenv:Envelope>
        """.trimIndent()
    }

    internal fun buildGetSignatureEnvelope(dataToBeDisplayed: String, apTransId: String): String {
        val escapedDataToBeDisplayed = xmlEscape(dataToBeDisplayed)
        val escapedApTransId = xmlEscape(apTransId)
        return """
            <soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:cli="http://client.mimza.uyap.gov.tr">
               <soapenv:Header/>
               <soapenv:Body>
                  <cli:getSignature>
                     <cli:dataToBeDisplayed>$escapedDataToBeDisplayed</cli:dataToBeDisplayed>
                     <cli:apTransId>$escapedApTransId</cli:apTransId>
                  </cli:getSignature>
               </soapenv:Body>
            </soapenv:Envelope>
        """.trimIndent()
    }

    private fun sendSoapRequest(
        action: String,
        envelope: String,
        readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS
    ): String {
        val url = URL(ENDPOINT)
        val conn = url.openConnection() as HttpURLConnection
        // Daha önce hiç timeout yoktu; yavaş/yanıt vermeyen sunucu sonsuz bekletiyordu.
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = readTimeoutMillis
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/xml;charset=UTF-8")
        conn.setRequestProperty("SOAPAction", action)

        try {
            conn.outputStream.use { os ->
                os.write(envelope.toByteArray(Charsets.UTF_8))
                os.flush()
            }

            val responseCode = conn.responseCode
            val stream = if (responseCode == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
            val response = stream.use { it.readBytes().toString(Charsets.UTF_8) }

            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("SOAP fault: $responseCode - $response")
            }
            return response
        } finally {
            // Connection'ı her koşulda kapat; aksi halde iptal edilen istekler
            // socket/connection sızdırır.
            conn.disconnect()
        }
    }

    // Telefon numarasını 11 haneli "0XXXXXXXXXX" formatına normalize eder.
    // Geçersizse null döner. Ayrı bir fonksiyon olarak test edilebilir kılındı.
    internal fun normalizePhoneNumber(raw: String): String? {
        var normalized = raw.trim()
            .replace(" ", "")
            .replace("-", "")
            .replace("(", "")
            .replace(")", "")

        if (normalized.startsWith("+90")) {
            normalized = normalized.substring(3)
        } else if (normalized.startsWith("90")) {
            normalized = normalized.substring(2)
        }

        if (normalized.length == 10 && normalized.startsWith("5")) {
            normalized = "0$normalized"
        }

        // Mobil imza yalnızca cep telefonları (05 ile başlayan 11 haneli) için geçerlidir.
        // Sabit hat numaraları (02xx) ve diğer prefix'ler reddedilir.
        return if (normalized.all { it.isDigit() } && normalized.length == 11 && normalized.startsWith("05")) {
            normalized
        } else {
            null
        }
    }

    private fun parseHashResponse(xml: String): HashResponse {
        val dbFactory = secureDocumentBuilderFactory()
        val dBuilder = dbFactory.newDocumentBuilder()
        val xmlDoc = dBuilder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        xmlDoc.documentElement.normalize()

        val getHashReturnNodes = xmlDoc.getElementsByTagName("getHashReturn")
        if (getHashReturnNodes.length == 0) {
            throw Exception("Invalid getHash SOAP response: missing getHashReturn")
        }

        val returnElem = getHashReturnNodes.item(0) as Element
        val apTransId = getElementText(returnElem, "apTransId")
        val fingerPrint = getElementText(returnElem, "fingerPrint")
        val message = getElementText(returnElem, "message")
        val resultCode = getElementText(returnElem, "resultCode").toIntOrNull() ?: RESULT_PENDING

        return HashResponse(apTransId, fingerPrint, message, resultCode)
    }

    private fun parseSignatureResponse(xml: String): SignResponse {
        val dbFactory = secureDocumentBuilderFactory()
        val dBuilder = dbFactory.newDocumentBuilder()
        val xmlDoc = dBuilder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        xmlDoc.documentElement.normalize()

        val getSignatureReturnNodes = xmlDoc.getElementsByTagName("getSignatureReturn")
        if (getSignatureReturnNodes.length == 0) {
            throw Exception("Invalid getSignature SOAP response: missing getSignatureReturn")
        }

        val returnElem = getSignatureReturnNodes.item(0) as Element
        val apTransId = getElementText(returnElem, "apTransId")
        val message = getElementText(returnElem, "message")
        val resultCode = getElementText(returnElem, "resultCode").toIntOrNull() ?: RESULT_PENDING
        val dataBase64 = getElementText(returnElem, "data")
        
        val dataBytes = if (dataBase64.isNotEmpty()) {
            try {
                // MIME çözücü: SOAP yanıtındaki satır kaydırmalarını tolere eder
                // (android.util.Base64.DEFAULT'un karşılığı).
                Base64.getMimeDecoder().decode(dataBase64)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        return SignResponse(apTransId, dataBytes, message, resultCode)
    }

    private fun getElementText(parent: Element, tagName: String): String {
        val nodes = parent.getElementsByTagName(tagName)
        if (nodes.length > 0) {
            return nodes.item(0).textContent ?: ""
        }
        val nodesByLocalName = parent.getElementsByTagNameNS("*", tagName)
        if (nodesByLocalName.length > 0) {
            return nodesByLocalName.item(0).textContent ?: ""
        }
        return ""
    }
}

