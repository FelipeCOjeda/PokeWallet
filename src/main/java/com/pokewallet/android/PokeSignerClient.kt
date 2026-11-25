package com.pokewallet.android

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

/**
 * Transporte BLE com o PokeSigner (ESP32-C6): GET_INFO, SIGN_PSBT e PING.
 * Protocolo em INTEGRACAO-POKEWALLET.md do repositório PokeSigner.
 *
 * Cada operação abre e fecha a conexão (findDevice → connect → request → close).
 * As permissões de runtime são checadas pela UI antes (ver [missingPermissions]).
 */
@SuppressLint("MissingPermission")
class PokeSignerClient(private val ctx: Context) : Closeable {

    companion object {
        val SERVICE: UUID = UUID.fromString("50530001-b5a3-f393-e0a9-50ca5e1d0001")
        val RX: UUID      = UUID.fromString("50530002-b5a3-f393-e0a9-50ca5e1d0001")
        val TX: UUID      = UUID.fromString("50530003-b5a3-f393-e0a9-50ca5e1d0001")
        val CCCD: UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        const val CMD_GET_INFO = 0x01
        const val CMD_SIGN     = 0x02
        const val CMD_PING     = 0x03
        const val CMD_CONFIRM  = 0x04
        const val CMD_CANCEL   = 0x05
        const val CMD_NEXT     = 0x06
        const val CMD_PREV     = 0x07
        const val RSP_INFO     = 0x81
        const val RSP_SIGNED   = 0x82
        const val RSP_PONG     = 0x83
        const val RSP_STATUS   = 0x90
        const val RSP_ERROR    = 0xFF

        private const val MAX_PAYLOAD = 32 * 1024
        private const val BOND_TIMEOUT_MS = 90_000L
        private const val GATT_TIMEOUT_MS = 15_000L

        /** Permissões de runtime que faltam (Android 12+: SCAN/CONNECT; ≤ 11: localização). */
        fun missingPermissions(ctx: Context): List<String> {
            val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            return needed.filter {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
            }
        }

        fun requiredPermissions(): Array<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

        fun le32(v: Int): ByteArray = byteArrayOf(
            (v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte()
        )
    }

    class SignerException(msg: String) : Exception(msg)

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    @Volatile private var mtu = 23

    private val frames = Channel<Pair<Int, ByteArray>>(Channel.UNLIMITED)
    private val rxBuffer = java.io.ByteArrayOutputStream()

    // Um slot por tipo de operação GATT — o Android só permite uma por vez.
    @Volatile private var connectWait: CompletableDeferred<Unit>? = null
    @Volatile private var mtuWait: CompletableDeferred<Unit>? = null
    @Volatile private var servicesWait: CompletableDeferred<Unit>? = null
    @Volatile private var writeWait: CompletableDeferred<Int>? = null
    @Volatile private var descriptorWait: CompletableDeferred<Int>? = null
    @Volatile private var disconnected = false

    // ── Scan ──────────────────────────────────────────────────────────

    suspend fun findDevice(timeoutMs: Long = 15_000): BluetoothDevice {
        val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null || !ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE))
            throw SignerException("Este aparelho não tem Bluetooth LE.")
        if (!adapter.isEnabled) throw SignerException("Ligue o Bluetooth do celular e tente de novo.")
        val scanner = adapter.bluetoothLeScanner
            ?: throw SignerException("Não foi possível iniciar o scan Bluetooth.")

        val found = CompletableDeferred<BluetoothDevice>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                found.complete(result.device)
            }
            override fun onScanFailed(errorCode: Int) {
                found.completeExceptionally(SignerException("Falha no scan Bluetooth (código $errorCode)."))
            }
        }
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, callback)
        try {
            return withTimeout(timeoutMs) { found.await() }
        } catch (e: TimeoutCancellationException) {
            throw SignerException(
                "PokeSigner não encontrado. Confira se a placa está ligada, já tem seed configurada " +
                    "e se o Bluetooth (e a localização, no Android 11 ou anterior) estão ligados."
            )
        } finally {
            try { scanner.stopScan(callback) } catch (_: Exception) {}
        }
    }

    // ── Conexão ───────────────────────────────────────────────────────

    suspend fun connect(device: BluetoothDevice) {
        disconnected = false
        connectWait = CompletableDeferred()
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw SignerException("Não foi possível conectar ao PokeSigner.")
        awaitGatt(connectWait!!, "conexão com o PokeSigner")
        val g = gatt!!

        mtuWait = CompletableDeferred()
        if (g.requestMtu(517)) {
            try { awaitGatt(mtuWait!!, "negociação de MTU") } catch (e: SignerException) {
                if (disconnected) throw e // falha de MTU sem queda é ignorada
            }
        }

        servicesWait = CompletableDeferred()
        if (!g.discoverServices()) throw SignerException("Não foi possível listar os serviços do PokeSigner.")
        awaitGatt(servicesWait!!, "descoberta de serviços")
        val svc = g.getService(SERVICE)
            ?: throw SignerException("Este dispositivo não é um PokeSigner (serviço não encontrado).")
        rxChar = svc.getCharacteristic(RX)
        txChar = svc.getCharacteristic(TX)
        if (rxChar == null || txChar == null) throw SignerException("PokeSigner com características GATT ausentes.")

        if (device.bondState != BluetoothDevice.BOND_BONDED) awaitBond(device)

        enableNotifications()
    }

    private suspend fun awaitBond(device: BluetoothDevice) {
        val bonded = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val dev = if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                if (dev?.address != device.address) return
                when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                    BluetoothDevice.BOND_BONDED -> bonded.complete(true)
                    BluetoothDevice.BOND_NONE   -> bonded.complete(false)
                }
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
        try {
            // A placa já pede segurança ao conectar: o bond pode estar em andamento.
            if (device.bondState != BluetoothDevice.BOND_BONDING) device.createBond()
            val ok = try {
                withTimeout(BOND_TIMEOUT_MS) { bonded.await() }
            } catch (e: TimeoutCancellationException) {
                throw SignerException("Tempo esgotado no pareamento. Digite o código de 6 dígitos que aparece na placa.")
            }
            if (!ok) throw SignerException("Pareamento cancelado ou código errado.")
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    private suspend fun enableNotifications() {
        val g = gatt!!
        val tx = txChar!!
        g.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(CCCD) ?: throw SignerException("PokeSigner sem descritor de notificação.")
        var status = writeDescriptor(g, cccd)
        if (status == 5 || status == 15) { // INSUFFICIENT_AUTHENTICATION / ENCRYPTION — repete após o bond
            awaitBond(g.device)
            status = writeDescriptor(g, cccd)
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw SignerException("Falha ao ativar notificações (GATT $status).")
    }

    private suspend fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor): Int {
        val wait = CompletableDeferred<Int>()
        descriptorWait = wait
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, value) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run { d.value = value; g.writeDescriptor(d) }
        }
        if (!started) throw SignerException("Não foi possível iniciar a escrita do descritor.")
        return awaitGatt(wait, "ativação de notificações")
    }

    // ── Requisição/resposta ───────────────────────────────────────────

    suspend fun request(
        cmd: Int,
        payload: ByteArray,
        timeoutMs: Long,
        onStatus: (String) -> Unit = {}
    ): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "PSBT grande demais para o PokeSigner (máx. 32 KB)." }
        sendFrame(cmd, payload)

        try {
            return withTimeout(timeoutMs) {
                var result: ByteArray? = null
                while (result == null) {
                    val (rsp, data) = try {
                        frames.receive()
                    } catch (e: Exception) {
                        throw if (e is SignerException) e else SignerException("Conexão com o PokeSigner caiu.")
                    }
                    when (rsp) {
                        RSP_STATUS -> onStatus(String(data, Charsets.UTF_8))
                        RSP_ERROR  -> throw SignerException(String(data, Charsets.UTF_8))
                        else       -> result = data
                    }
                }
                result
            }
        } catch (e: TimeoutCancellationException) {
            throw SignerException("O PokeSigner não respondeu a tempo.")
        }
    }

    private val writeMutex = kotlinx.coroutines.sync.Mutex()

    /** Escreve um quadro [cmd][len][payload]; serializado, pois o GATT só aceita uma escrita por vez. */
    private suspend fun sendFrame(cmd: Int, payload: ByteArray) = writeMutex.withLock {
        val frame = byteArrayOf(cmd.toByte()) + le32(payload.size) + payload
        val chunk = (mtu - 3).coerceIn(20, 512)

        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + chunk, frame.size)
            val piece = frame.copyOfRange(offset, end)
            var status = writeChunk(piece)
            if (status == 5 || status == 15) { // exige bond: aguarda e repete a mesma fatia
                awaitBond(gatt!!.device)
                status = writeChunk(piece)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) throw SignerException("Falha ao enviar ao PokeSigner (GATT $status).")
            offset = end
        }
    }

    // Comandos de controle durante um SIGN_PSBT pendente (v0.4.0+): o resultado vem no SIGN.
    suspend fun confirmCode(code: String) = sendFrame(CMD_CONFIRM, code.toByteArray(Charsets.US_ASCII))
    suspend fun cancelSigning() = sendFrame(CMD_CANCEL, ByteArray(0))
    suspend fun nextPage() = sendFrame(CMD_NEXT, ByteArray(0))
    suspend fun prevPage() = sendFrame(CMD_PREV, ByteArray(0))

    private suspend fun writeChunk(piece: ByteArray): Int {
        val g = gatt ?: throw SignerException("Conexão com o PokeSigner caiu.")
        val rx = rxChar!!
        val wait = CompletableDeferred<Int>()
        writeWait = wait
        val started = if (Build.VERSION.SDK_INT >= 33) {
            g.writeCharacteristic(rx, piece, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION") run {
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                rx.value = piece
                g.writeCharacteristic(rx)
            }
        }
        if (!started) throw SignerException("Não foi possível enviar ao PokeSigner.")
        return awaitGatt(wait, "envio ao PokeSigner")
    }

    suspend fun ping(): String = String(request(CMD_PING, ByteArray(0), 10_000), Charsets.UTF_8)

    suspend fun getInfo(testnet: Boolean, account: Int = 0): JSONObject = JSONObject(
        String(
            request(CMD_GET_INFO, byteArrayOf(if (testnet) 1 else 0) + le32(account), 15_000),
            Charsets.UTF_8
        )
    )

    suspend fun signPsbt(psbt: ByteArray, onStatus: (String) -> Unit = {}): ByteArray =
        request(CMD_SIGN, psbt, 200_000, onStatus)

    override fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        frames.close()
    }

    // ── Utilitários ───────────────────────────────────────────────────

    private suspend fun <T> awaitGatt(wait: CompletableDeferred<T>, what: String): T = try {
        withTimeout(GATT_TIMEOUT_MS) { wait.await() }
    } catch (e: TimeoutCancellationException) {
        throw SignerException("Tempo esgotado na $what.")
    }

    private fun failAllPending(e: Exception) {
        connectWait?.completeExceptionally(e)
        mtuWait?.completeExceptionally(e)
        servicesWait?.completeExceptionally(e)
        writeWait?.completeExceptionally(e)
        descriptorWait?.completeExceptionally(e)
    }

    /** Acumula bytes recebidos; emite cada quadro [cmd][len LE][payload] completo. */
    private fun onBytes(value: ByteArray) {
        synchronized(rxBuffer) {
        rxBuffer.write(value)
        while (true) {
            val buf = rxBuffer.toByteArray()
            if (buf.size < 5) return
            val len = (buf[1].toInt() and 0xFF) or ((buf[2].toInt() and 0xFF) shl 8) or
                ((buf[3].toInt() and 0xFF) shl 16) or ((buf[4].toInt() and 0xFF) shl 24)
            if (len < 0 || len > MAX_PAYLOAD) {
                rxBuffer.reset()
                frames.close(SignerException("Resposta inválida do PokeSigner."))
                return
            }
            if (buf.size < 5 + len) return
            frames.trySend((buf[0].toInt() and 0xFF) to buf.copyOfRange(5, 5 + len))
            rxBuffer.reset()
            if (buf.size > 5 + len) rxBuffer.write(buf, 5 + len, buf.size - 5 - len)
        }
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connectWait?.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                disconnected = true
                val e = SignerException("Conexão com o PokeSigner caiu.")
                failAllPending(e)
                frames.close(e)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            mtuWait?.complete(Unit)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesWait?.complete(Unit)
            else servicesWait?.completeExceptionally(SignerException("Falha ao listar serviços (GATT $status)."))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            writeWait?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            descriptorWait?.complete(status)
        }

        // API 33+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            if (ch.uuid == TX) onBytes(value)
        }

        // API < 33 (a partir da 33 o framework chama só a versão com value)
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && ch.uuid == TX) {
                @Suppress("DEPRECATION") onBytes(ch.value ?: return)
            }
        }
    }
}
