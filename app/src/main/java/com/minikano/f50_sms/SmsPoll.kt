package com.minikano.f50_sms

import android.content.Context
import android.net.Uri
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class SmsInfo(val address: String, val body: String, val timestamp: Long)

object SmsPoll {
    private var lastSms: SmsInfo? = null

    //store
    private val PREFS_NAME = "kano_ZTE_store"

    fun checkNewSmsAndSend(context: Context) {
        val sms = getLatestSms(context) ?: return

        val now = System.currentTimeMillis()
        val minute = 2
        val withinMin = now - sms.timestamp <= minute * 60 * 1000
        val isNew = lastSms == null || sms != lastSms

        if (withinMin && isNew) {
            Log.d("kano_ZTE_LOG", "收到新短信: ${sms.address} - ${sms.body}")
            lastSms = sms
            // 在这里做转发处理
            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            when (sharedPrefs.getString("kano_sms_forward_method", "")?.uppercase()) {
            "SMTP" -> forwardByEmail(lastSms, context)
            "CURL" -> forwardSmsByCurl(lastSms, context)
            "WEWORK" -> forwardByWeWork(lastSms, context) // 新增这行
            else -> Log.e("kano_ZTE_LOG", "未知的转发方式")
        }
        // 修改这里结束
    } else {
        Log.d("kano_ZTE_LOG", "无新短信，短信是否${minute}分钟内：$withinMin,短信是否为新：$isNew")
    }
}

    //通过curl转发
    fun forwardSmsByCurl(sms_data:SmsInfo?,context: Context) {
        if (sms_data == null) return
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val originalCurl = sharedPrefs.getString("kano_sms_curl", null)
        if (originalCurl.isNullOrEmpty()) {
            Log.e("kano_ZTE_LOG", "curl 配置错误：kano_sms_curl 为空")
            return
        }

        Log.d("kano_ZTE_LOG", "开始转发短信...（CURL）")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val smsText = """${sms_data!!.body.trimStart()}
        📩 来自：${sms_data!!.address}
        ⏰ 时间：${formatter.format(Instant.ofEpochMilli(sms_data!!.timestamp))}
        """.trimIndent()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

        //替换并发送
        val replacedCurl = originalCurl.replace("{{sms}}", smsText)
        KanoCURL(context).send(replacedCurl)
    }

    //通过SMTP邮件转发
    fun forwardByEmail(sms_data:SmsInfo?,context: Context) {
        if (sms_data == null) return
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val smtpHost = sharedPrefs.getString("kano_smtp_host", null)
        if (smtpHost.isNullOrEmpty()) {
            Log.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_host 为空")
            return
        }

        val smtpTo = sharedPrefs.getString("kano_smtp_to", null)
        if (smtpTo.isNullOrEmpty()) {
            Log.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_to 为空")
            return
        }

        val smtpPort = sharedPrefs.getString("kano_smtp_port", null)
        if (smtpPort.isNullOrEmpty()) {
            Log.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_port 为空")
            return
        }

        val username = sharedPrefs.getString("kano_smtp_username", null)
        if (username.isNullOrEmpty()) {
            Log.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_username 为空")
            return
        }

        val password = sharedPrefs.getString("kano_smtp_password", null)
        if (password.isNullOrEmpty()) {
            Log.e("kano_ZTE_LOG", "SMTP 配置错误：kano_smtp_password 为空")
            return
        }

        val smtpClient = KanoSMTP(smtpHost, smtpPort, username, password)

        Log.d("kano_ZTE_LOG", "开始转发短信...(SMTP)")

        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault())
        val previewText = sms_data!!.body.trimStart().let {
            if (it.length > 37) it.take(37) + "…" else it
        }
        smtpClient.sendEmail(
            to = smtpTo,
            subject = previewText,
            body = """${sms_data!!.body.trimStart()}
            📩 <b>来自：</b>${sms_data!!.address}
            ⏰ <b>时间：</b>${formatter.format(Instant.ofEpochMilli(sms_data!!.timestamp))}
            <div style="text-align=center"><i>Powered by <a href="">UFI-TOOLS</a></i></div>
            """.trimIndent()
        )
    }
// 新增企业微信转发方法↓↓↓↓↓↓↓↓↓↓
private fun forwardByWeWork(smsData: SmsInfo?, context: Context) {
    if (smsData == null) return

    // 在子线程执行网络操作
    Thread {
        try {
            val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            
            // 读取配置参数
            val corpid = sharedPrefs.getString("kano_wework_corpid", "") ?: ""
            val agentid = sharedPrefs.getString("kano_wework_agentid", "") ?: ""
            val secret = sharedPrefs.getString("kano_wework_secret", "") ?: ""
            val touser = sharedPrefs.getString("kano_wework_touser", "@all") ?: "@all"

            // 参数检查
            if (corpid.isEmpty() || agentid.isEmpty() || secret.isEmpty()) {
                throw Exception("企业微信配置不完整")
            }

            Log.d("kano_ZTE_LOG", "正在通过企业微信转发...")

            // 1. 获取访问令牌
            val tokenUrl = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=$corpid&corpsecret=$secret"
            val tokenResponse = URL(tokenUrl).readText()
            val tokenJson = JSONObject(tokenResponse)
            if (tokenJson.getInt("errcode") != 0) {
                throw Exception("获取Token失败: ${tokenJson.getString("errmsg")}")
            }
            val accessToken = tokenJson.getString("access_token")

            // 2. 格式化消息内容
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
            val content = """
                ${smsData.body.trim()}
                📩 来自：${smsData.address}
                ⏰ 时间：${formatter.format(Instant.ofEpochMilli(smsData.timestamp))}
            """.trimIndent()

            // 3. 构建消息JSON
            val msgJson = """
                {
                    "touser": "$touser",
                    "msgtype": "text",
                    "agentid": $agentid,
                    "text": {
                        "content": "短信通知：\n$content"
                    }
                }
            """.trimIndent()

            // 4. 发送请求
            val connection = URL("https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=$accessToken")
                .openConnection() as HttpURLConnection
            
            connection.apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                connectTimeout = 5000 // 5秒超时
                readTimeout = 5000
            }

            // 写入请求体
            connection.outputStream.use { it.write(msgJson.toByteArray()) }

            // 检查响应状态
            if (connection.responseCode != 200) {
                throw Exception("HTTP错误码：${connection.responseCode}")
            }

            // 检查企业微信返回结果
            val response = connection.inputStream.bufferedReader().readText()
            val resJson = JSONObject(response)
            if (resJson.getInt("errcode") != 0) {
                throw Exception("发送失败：${resJson.getString("errmsg")}")
            }

            Log.d("kano_ZTE_LOG", "企业微信消息发送成功！")

        } catch (e: Exception) {
            Log.e("kano_ZTE_LOG", "企业微信发送失败: ${e.message}")
        }
    }.start() // 启动线程
}
    fun getLatestSms(context: Context): SmsInfo? {
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf("address", "body", "date")
        val sortOrder = "date DESC"

        return try {
            val cursor = context.contentResolver.query(uri, projection, null, null, sortOrder)
            cursor?.use {
                if (it.moveToFirst()) {
                    val address = it.getString(it.getColumnIndexOrThrow("address"))
                    val body = it.getString(it.getColumnIndexOrThrow("body"))
                    val date = it.getLong(it.getColumnIndexOrThrow("date"))
                    SmsInfo(address, body, date)
                } else null
            }
        } catch (e: Exception) {
            Log.e("kano_ZTE_LOG", "没有短信权限，读不到短信呢", e)
            null
        }
    }
}