package com.pokewallet.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pokewallet.R
import com.pokewallet.crypto.FeeTimeEstimator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WalletFragment : Fragment() {

    private lateinit var viewModel: WalletViewModel

    private var qrTargetAddressField: TextInputEditText? = null
    private var airGappedSignJob: kotlinx.coroutines.Job? = null
    private var airGappedBroadcastJob: kotlinx.coroutines.Job? = null

    /** Quando setado, o próximo resultado do scanner vai pra cá (conteúdo
     *  bruto, sem tratamento de endereço/URI) em vez do fluxo padrão de
     *  colar num campo de endereço — usado pelo fluxo air-gapped (escanear
     *  PSBT ou tx assinada de volta). Sempre limpo depois de um scan. */
    private var qrScanCallback: ((String) -> Unit)? = null

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult
        val callback = qrScanCallback
        qrScanCallback = null
        if (callback != null) {
            callback(content)
        } else {
            val address = content.removePrefix("bitcoin:").substringBefore("?")
            qrTargetAddressField?.setText(address)
        }
    }

    private fun launchQrScan(prompt: String, onResult: (String) -> Unit) {
        qrScanCallback = onResult
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(prompt)
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        qrScanLauncher.launch(options)
    }

    /** Lê um arquivo .psbt escolhido pelo usuário (Storage Access
     *  Framework — sem permissão de storage nenhuma) e devolve o base64
     *  pronto pra viewModel.signAirGappedPsbt(). Aceita tanto o formato
     *  BIP174 binário "de verdade" (magic bytes 0x70 0x73 0x62 0x74 0xff —
     *  o que apps como BlueWallet/Sparrow exportam por padrão) QUANTO um
     *  arquivo de texto já em base64 — detecta automaticamente pelos
     *  primeiros bytes. null se não conseguiu ler ou o usuário cancelou. */
    private var psbtFileImportCallback: ((String?) -> Unit)? = null

    private val psbtFileImportLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val callback = psbtFileImportCallback
        psbtFileImportCallback = null
        if (uri == null) {
            callback?.invoke(null)
            return@registerForActivityResult
        }
        val content = try {
            val bytes = requireContext().contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val psbtMagic = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
            when {
                bytes == null -> null
                bytes.size >= 5 && bytes.copyOfRange(0, 5).contentEquals(psbtMagic) ->
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                else -> String(bytes, Charsets.UTF_8).trim()
            }
        } catch (_: Exception) {
            null
        }
        callback?.invoke(content)
    }

    private fun launchPsbtFileImport(onResult: (String?) -> Unit) {
        psbtFileImportCallback = onResult
        psbtFileImportLauncher.launch("*/*")
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_wallet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[WalletViewModel::class.java]

        val layoutTxHistory  = view.findViewById<LinearLayout>(R.id.layout_tx_history)
        val rowHomeSwitcher  = view.findViewById<LinearLayout>(R.id.row_home_wallet_switcher)
        val tvHomeWalletName = view.findViewById<TextView>(R.id.tv_home_wallet_name)
        val tvHomeChevron    = view.findViewById<TextView>(R.id.tv_home_switch_chevron)
        val tvNetworkBadge   = view.findViewById<TextView>(R.id.tv_network_badge)
        val tvBalanceSats    = view.findViewById<TextView>(R.id.tv_balance_sats)
        val tvBalanceBtc     = view.findViewById<TextView>(R.id.tv_balance_btc)
        val tvBalancePending = view.findViewById<TextView>(R.id.tv_balance_pending)
        val progressScan     = view.findViewById<ProgressBar>(R.id.progress_scan)
        val tvScanStatus     = view.findViewById<TextView>(R.id.tv_scan_status)
        val tvLastScan       = view.findViewById<TextView>(R.id.tv_last_scan)
        val tvScanError      = view.findViewById<TextView>(R.id.tv_scan_error)
        val tvCrossCheckWarning = view.findViewById<TextView>(R.id.tv_balance_cross_check_warning)
        val btnReceive       = view.findViewById<MaterialButton>(R.id.btn_receive)
        val btnSend          = view.findViewById<MaterialButton>(R.id.btn_send)
        val cardBag          = view.findViewById<View>(R.id.card_bag)
        val tvWalletName     = view.findViewById<TextView>(R.id.tv_wallet_name)
        val imgWalletType    = view.findViewById<ImageView>(R.id.img_wallet_type)
        val btnRenameWallet  = view.findViewById<TextView>(R.id.btn_rename_wallet)
        val btnSwitchWallet  = view.findViewById<MaterialButton>(R.id.btn_switch_wallet)
        val btnNewWallet     = view.findViewById<MaterialButton>(R.id.btn_new_wallet)
        val btnViewAddresses = view.findViewById<MaterialButton>(R.id.btn_view_addresses)
        val btnViewUtxos     = view.findViewById<MaterialButton>(R.id.btn_view_utxos)
        val btnViewPublicKey = view.findViewById<MaterialButton>(R.id.btn_view_public_key)
        val btnForceFullRescan = view.findViewById<MaterialButton>(R.id.btn_force_full_rescan)
        val tvSeedBackup     = view.findViewById<TextView>(R.id.tv_seed_backup_status)
        val btnSignAirGapped = view.findViewById<MaterialButton>(R.id.btn_sign_airgapped_psbt)
        val btnForget        = view.findViewById<MaterialButton>(R.id.btn_forget)
        val btnThemeLight    = view.findViewById<MaterialButton>(R.id.btn_theme_light)
        val btnThemeDark     = view.findViewById<MaterialButton>(R.id.btn_theme_dark)
        val tvElectrumStatus = view.findViewById<TextView>(R.id.tv_electrum_status)
        val btnConfigureElectrum = view.findViewById<MaterialButton>(R.id.btn_configure_electrum)
        val tvTorStatus      = view.findViewById<TextView>(R.id.tv_tor_status)
        val btnConfigureTor  = view.findViewById<MaterialButton>(R.id.btn_configure_tor)
        val tvSpOracleStatus = view.findViewById<TextView>(R.id.tv_sp_oracle_status)
        val btnConfigureSpOracle = view.findViewById<MaterialButton>(R.id.btn_configure_sp_oracle)
        val btnSyncSilentPayments = view.findViewById<MaterialButton>(R.id.btn_sync_silent_payments)
        val cardError        = view.findViewById<View>(R.id.card_error)
        val tvError          = view.findViewById<TextView>(R.id.tv_error)
        val bottomNav        = view.findViewById<BottomNavigationView>(R.id.bottom_nav)

        bottomNav.setOnItemReselectedListener { item ->
            // BottomNavigationView não dispara setOnItemSelectedListener quando o
            // usuário toca na aba que já está ativa — sem isso, tocar em Home de
            // novo (já estando nela) não atualizava o saldo.
            if (item.itemId == R.id.nav_base) viewModel.refreshNow()
        }

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_base -> {
                    cardBag.visibility = View.GONE
                    viewModel.refreshNow()
                    true
                }
                R.id.nav_receive -> {
                    val pair = viewModel.getReceiveAddress()
                    if (pair != null) showReceiveDialog(pair.first, pair.second)
                    false
                }
                R.id.nav_send -> {
                    openSend()
                    false
                }
                R.id.nav_bag -> {
                    cardBag.visibility = View.VISIBLE
                    tvElectrumStatus.text = NodePrefs.statusLabel(requireContext())
                    tvTorStatus.text = TorPrefs.statusLabel(requireContext())
                    updateSpOracleStatus(tvSpOracleStatus)
                    true
                }
                else -> false
            }
        }

        btnReceive.setOnClickListener {
            val pair = viewModel.getReceiveAddress()
            if (pair != null) showReceiveDialog(pair.first, pair.second)
        }

        btnSend.setOnClickListener { openSend() }
        btnForget.setOnClickListener { confirmForget() }

        btnRenameWallet.setOnClickListener {
            val current = viewModel.walletState.value as? WalletState.Loaded ?: return@setOnClickListener
            showRenameWalletDialog(current.displayName)
        }
        btnSwitchWallet.setOnClickListener {
            if (!canChangeActiveWallet()) return@setOnClickListener
            showSwitchWalletDialog()
        }
        btnNewWallet.setOnClickListener {
            if (!canChangeActiveWallet()) return@setOnClickListener
            showAddWalletChoiceDialog()
        }
        btnViewAddresses.setOnClickListener { showAddressListDialog() }
        btnViewUtxos.setOnClickListener { showUtxoListDialog() }
        btnViewPublicKey.setOnClickListener { showPublicKeyDialog() }
        btnForceFullRescan.setOnClickListener { viewModel.forceFullRescan() }
        btnSignAirGapped.setOnClickListener { startSignAirGappedPsbtFlow() }
        rowHomeSwitcher.setOnClickListener {
            if (viewModel.listKnownWallets().size <= 1) return@setOnClickListener
            if (!canChangeActiveWallet()) return@setOnClickListener
            showSwitchWalletDialog()
        }

        ThemePrefs.bindToggle(requireActivity(), btnThemeLight, btnThemeDark)

        tvElectrumStatus.text = NodePrefs.statusLabel(requireContext())
        btnConfigureElectrum.setOnClickListener { showElectrumNodeDialog(tvElectrumStatus) }

        tvTorStatus.text = TorPrefs.statusLabel(requireContext())
        btnConfigureTor.setOnClickListener { showTorConfigDialog(tvTorStatus) }

        updateSpOracleStatus(tvSpOracleStatus)
        btnConfigureSpOracle.setOnClickListener { showSpOracleDialog(tvSpOracleStatus) }
        btnSyncSilentPayments.setOnClickListener { triggerSilentPaymentsSync(tvSpOracleStatus, btnSyncSilentPayments) }

        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.walletState.collectLatest { state ->
                when (state) {
                    is WalletState.Loaded -> {
                        cardError.visibility = View.GONE
                        tvNetworkBadge.text  = state.network.name

                        if (state.balanceSats != null) {
                            val btc = state.balanceSats / 100_000_000.0
                            tvBalanceSats.text = "%,d sat".format(state.balanceSats)
                            tvBalanceBtc.text  = "%.8f BTC".format(btc)

                            if (state.pendingSats != null && state.pendingSats > 0L) {
                                tvBalancePending.text       = "⏳ Pendente: +%,d sat".format(state.pendingSats)
                                tvBalancePending.visibility = View.VISIBLE
                            } else {
                                tvBalancePending.visibility = View.GONE
                            }
                        }

                        if (state.isScanning) {
                            progressScan.visibility = View.VISIBLE
                            tvScanStatus.visibility = View.VISIBLE
                            tvScanStatus.text       = state.scanStatus ?: "Varrendo…"
                        } else {
                            progressScan.visibility = View.GONE
                            tvScanStatus.visibility = View.GONE
                        }

                        if (state.lastScanTime != null) {
                            tvLastScan.text       = "Atualizado: ${timeFmt.format(state.lastScanTime)}"
                            tvLastScan.visibility = View.VISIBLE
                        }

                        if (state.lastScanError != null) {
                            tvScanError.text       = "⚠️ Última verificação de saldo falhou: ${state.lastScanError} — o app tenta de novo sozinho em alguns minutos."
                            tvScanError.visibility = View.VISIBLE
                        } else {
                            tvScanError.visibility = View.GONE
                        }

                        if (state.balanceCrossCheckWarning != null) {
                            tvCrossCheckWarning.text       = state.balanceCrossCheckWarning
                            tvCrossCheckWarning.visibility = View.VISIBLE
                        } else {
                            tvCrossCheckWarning.visibility = View.GONE
                        }

                        tvWalletName.text = state.displayName
                        imgWalletType.imageTintList = if (state.isWatchOnly)
                            ContextCompat.getColorStateList(requireContext(), R.color.pokeball_gray)
                        else null
                        tvSeedBackup.visibility = if (state.isWatchOnly) View.GONE else View.VISIBLE
                        // Assinar PSBT air-gapped só faz sentido com seed local —
                        // watch-only é justamente quem PRECISA de outro aparelho pra isso.
                        btnSignAirGapped.visibility = if (state.isWatchOnly) View.GONE else View.VISIBLE

                        val hasMultipleWallets = viewModel.listKnownWallets().size > 1
                        btnSwitchWallet.visibility = if (hasMultipleWallets) View.VISIBLE else View.GONE
                        tvHomeWalletName.text = "⚡ ${state.displayName}"
                        tvHomeChevron.visibility = if (hasMultipleWallets) View.VISIBLE else View.GONE
                    }
                    is WalletState.Error -> {
                        tvError.text            = state.message
                        cardError.visibility    = View.VISIBLE
                        progressScan.visibility = View.GONE
                        tvScanStatus.visibility = View.GONE
                    }
                    else -> {}
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.txHistory.collectLatest { txList ->
                layoutTxHistory.removeAllViews()
                if (txList.isEmpty()) {
                    layoutTxHistory.addView(makeTxPlaceholder())
                } else {
                    for (tx in txList) layoutTxHistory.addView(makeTxRow(tx))
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingTxEvent.collectLatest { sats ->
                Toast.makeText(
                    requireContext(),
                    "⏳ Transação pendente chegando!\n+%,d sat a caminho".format(sats),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun showReceiveDialog(address: String, index: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_receive, null)
        val tvAddress      = dialogView.findViewById<TextView>(R.id.tv_address)
        val tvAddressIndex = dialogView.findViewById<TextView>(R.id.tv_address_index)
        val imgQr          = dialogView.findViewById<android.widget.ImageView>(R.id.img_qr)
        val btnToggleSp    = dialogView.findViewById<MaterialButton>(R.id.btn_toggle_silent_payment)

        fun renderQr(value: String) {
            try {
                val sizePx = resources.displayMetrics.density.let { (220 * it).toInt() }
                imgQr.setImageBitmap(generateQrBitmap(value, sizePx))
                imgQr.visibility = View.VISIBLE
            } catch (_: Exception) {
                imgQr.visibility = View.GONE
            }
        }

        // Estado do que está EXIBIDO agora no diálogo (normal vs. Silent
        // Payments) — o botão de copiar sempre copia o que está na tela,
        // não sempre o endereço normal.
        var displayedAddress = address
        tvAddress.text = address
        tvAddressIndex.text = "Índice de derivação: $index"
        renderQr(address)

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_copy_address).setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Bitcoin address", displayedAddress))
            Toast.makeText(requireContext(), "Endereço copiado!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        // Endereço Silent Payments (BIP-352) — fixo por carteira, sem
        // índice. Só carteira com seed neste aparelho (watch-only ainda
        // não suporta, ver Fase 5 do plano); botão fica escondido nos
        // outros casos.
        val spAddress = viewModel.getSilentPaymentAddress()
        if (spAddress != null) {
            btnToggleSp.visibility = View.VISIBLE
            var showingSp = false
            btnToggleSp.setOnClickListener {
                showingSp = !showingSp
                if (showingSp) {
                    displayedAddress = spAddress
                    tvAddress.text = spAddress
                    tvAddressIndex.text = "Silent Payments (BIP-352) — endereço fixo, não avança índice"
                    renderQr(spAddress)
                    btnToggleSp.text = "↩ Ver endereço normal"
                } else {
                    displayedAddress = address
                    tvAddress.text = address
                    tvAddressIndex.text = "Índice de derivação: $index"
                    renderQr(address)
                    btnToggleSp.text = "🔒 Ver endereço Silent Payments"
                }
            }
        }

        dialog.show()
    }

    private fun showPublicKeyDialog() {
        val accountOrigin = viewModel.getAccountOrigin()
        if (accountOrigin == null) {
            Toast.makeText(requireContext(), "Aguarde a carteira terminar de carregar.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_public_key, null)
        dialogView.findViewById<TextView>(R.id.tv_account_origin).text = accountOrigin

        val imgQr = dialogView.findViewById<android.widget.ImageView>(R.id.img_qr_public_key)
        try {
            val sizePx = resources.displayMetrics.density.let { (220 * it).toInt() }
            imgQr.setImageBitmap(generateQrBitmap(accountOrigin, sizePx))
        } catch (_: Exception) {
            imgQr.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_copy_account_origin).setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Account origin", accountOrigin))
            Toast.makeText(requireContext(), "Chave pública copiada!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    /** Lado signer do fluxo air-gapped (Fase C4): traz o PSBT não-assinado
     *  montado pela carteira watch-only (deste app ou de qualquer outra
     *  compatível, ex.: BlueWallet/Sparrow) por QR, arquivo .psbt ou texto
     *  colado, e assina com a seed local. */
    private fun startSignAirGappedPsbtFlow() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_sign_airgapped_psbt_input, null)
        val etPsbt        = dialogView.findViewById<TextInputEditText>(R.id.et_psbt_to_sign)
        val tvError       = dialogView.findViewById<TextView>(R.id.tv_sign_psbt_input_error)
        val btnScan       = dialogView.findViewById<MaterialButton>(R.id.btn_scan_psbt_to_sign)
        val btnImportFile = dialogView.findViewById<MaterialButton>(R.id.btn_import_psbt_file)
        val btnConfirm    = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_psbt_to_sign)
        val btnCancel     = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_sign_psbt_input)

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnScan.setOnClickListener {
            dialog.dismiss()
            launchQrScan("Aponte para o QR do PSBT (tela \"Assine no outro aparelho\")") { scanned ->
                signAirGappedPsbtAndObserve(scanned)
            }
        }

        btnImportFile.setOnClickListener {
            launchPsbtFileImport { content ->
                if (content == null) {
                    tvError.text = "Não consegui ler o arquivo — confira se é um .psbt válido."
                    tvError.visibility = View.VISIBLE
                } else {
                    dialog.dismiss()
                    signAirGappedPsbtAndObserve(content)
                }
            }
        }

        btnConfirm.setOnClickListener {
            val text = etPsbt.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                tvError.text = "Cole o texto do PSBT antes de confirmar."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            tvError.visibility = View.GONE
            dialog.dismiss()
            signAirGappedPsbtAndObserve(text)
        }

        dialog.show()
    }

    /** Chama viewModel.signAirGappedPsbt() e observa o resultado — mesmo
     *  fluxo independente de o PSBT ter vindo por QR, arquivo ou texto
     *  colado (startSignAirGappedPsbtFlow()). */
    private fun signAirGappedPsbtAndObserve(psbtBase64: String) {
        viewModel.signAirGappedPsbt(psbtBase64)

        // Cancela um coletor anterior antes de abrir outro — sem isso, toques
        // repetidos no botão empilhariam vários coletores no mesmo StateFlow
        // e um resultado só dispararia o diálogo/toast várias vezes.
        airGappedSignJob?.cancel()
        airGappedSignJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.airGappedSignState.collectLatest { state ->
                when (state) {
                    is AirGappedSignState.Signing -> {
                        Toast.makeText(requireContext(), "Assinando…", Toast.LENGTH_SHORT).show()
                    }
                    is AirGappedSignState.Success -> {
                        showAirGappedSignResultDialog(state.rawTxHex, state.txid)
                        viewModel.resetAirGappedSignState()
                    }
                    is AirGappedSignState.Error -> {
                        Toast.makeText(requireContext(), state.message, Toast.LENGTH_LONG).show()
                        viewModel.resetAirGappedSignState()
                    }
                    is AirGappedSignState.Idle -> {}
                }
            }
        }
    }

    private fun showAirGappedSignResultDialog(rawTxHex: String, txid: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_airgapped_sign_result, null)
        dialogView.findViewById<TextView>(R.id.tv_signed_txid).text = "txid: $txid"
        dialogView.findViewById<TextView>(R.id.tv_signed_tx_hex).text = rawTxHex

        val imgQr = dialogView.findViewById<android.widget.ImageView>(R.id.img_qr_signed_tx)
        try {
            val sizePx = resources.displayMetrics.density.let { (240 * it).toInt() }
            imgQr.setImageBitmap(generateQrBitmap(rawTxHex, sizePx))
        } catch (_: Exception) {
            imgQr.visibility = View.GONE
            Toast.makeText(requireContext(), "TX assinada grande demais pra um QR só (muitos inputs) — copie o texto abaixo e cole no outro aparelho.", Toast.LENGTH_LONG).show()
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_copy_signed_tx_hex).setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Signed tx hex", rawTxHex))
            Toast.makeText(requireContext(), "Texto copiado!", Toast.LENGTH_SHORT).show()
        }

        dialogView.findViewById<MaterialButton>(R.id.btn_close_sign_result).setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAddressListDialog() {
        val addresses = viewModel.getAddressList()
        if (addresses == null) {
            Toast.makeText(requireContext(), "Aguarde a carteira terminar de carregar.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView    = layoutInflater.inflate(R.layout.dialog_address_list, null)
        val rgChain       = dialogView.findViewById<RadioGroup>(R.id.rg_address_chain)
        val layoutList    = dialogView.findViewById<LinearLayout>(R.id.layout_address_list)
        val btnClose       = dialogView.findViewById<MaterialButton>(R.id.btn_close_address_list)

        fun render(chain: Int) {
            layoutList.removeAllViews()
            val rows = addresses.filter { it.chain == chain }.sortedByDescending { it.index }
            if (rows.isEmpty()) {
                layoutList.addView(makeTxPlaceholder())
            } else {
                for (row in rows) layoutList.addView(makeAddressRow(row))
            }
        }

        rgChain.setOnCheckedChangeListener { _, checkedId ->
            render(if (checkedId == R.id.rb_chain_internal) 1 else 0)
        }
        render(0)

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun makeAddressRow(row: AddressRow): View {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }

        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        val tvIndex = TextView(requireContext()).apply {
            text      = "#${row.index}"
            textSize  = 12f
            typeface  = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvUsed = TextView(requireContext()).apply {
            text = if (row.used) "✔ usado" else "○ não usado"
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(),
                if (row.used) R.color.green_status else R.color.gb_border_soft))
        }

        headerRow.addView(tvIndex)
        headerRow.addView(tvUsed)

        val tvAddress = TextView(requireContext()).apply {
            text          = row.address
            textSize      = 12f
            typeface      = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
        }

        val tvBalance = TextView(requireContext()).apply {
            text     = "%,d sat".format(row.balanceSats)
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(),
                if (row.balanceSats > 0) R.color.bitcoin_orange else R.color.gb_border_soft))
        }

        container.addView(headerRow)
        container.addView(tvAddress)
        container.addView(tvBalance)

        container.setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Bitcoin address", row.address))
            Toast.makeText(requireContext(), "Endereço copiado!", Toast.LENGTH_SHORT).show()
        }

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            )
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
        }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(container)
            addView(divider)
        }
    }

    /**
     * @param selectionMode false = tela de gerenciamento (toque congela/
     *   descongela, como sempre foi). true = modo de escolha pro envio
     *   manual (Fase B3): toque marca/desmarca, UTXOs congelados nem
     *   aparecem (nunca podem ser gastos), e o botão inferior confirma a
     *   seleção em vez de só fechar.
     */
    private fun showUtxoListDialog(
        selectionMode: Boolean = false,
        initialSelection: Set<String> = emptySet(),
        onSelectionConfirmed: ((Set<String>) -> Unit)? = null
    ) {
        val utxos = viewModel.getUtxoList()
        if (utxos == null) {
            Toast.makeText(requireContext(), "Aguarde a carteira terminar de carregar.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView = layoutInflater.inflate(R.layout.dialog_utxo_list, null)
        val layoutList = dialogView.findViewById<LinearLayout>(R.id.layout_utxo_list)
        val btnClose    = dialogView.findViewById<MaterialButton>(R.id.btn_close_utxo_list)

        // Estado local otimista: reflete o toque na hora, sem esperar a
        // persistência assíncrona no ViewModel terminar — a chamada real
        // (viewModel.toggleUtxoFrozen) roda em paralelo.
        val frozenState = utxos.associateTo(mutableMapOf()) { it.key to it.frozen }
        val selected    = initialSelection.toMutableSet()

        // Congelado nunca é gastável — nem aparece como opção no modo de
        // seleção (evita escolher algo que buildSignedTx() vai rejeitar).
        val visibleUtxos = if (selectionMode) utxos.filterNot { it.frozen } else utxos

        fun render() {
            layoutList.removeAllViews()
            if (visibleUtxos.isEmpty()) {
                layoutList.addView(makeTxPlaceholder())
            } else if (selectionMode) {
                for (utxo in visibleUtxos) {
                    layoutList.addView(makeUtxoSelectableRow(utxo, selected.contains(utxo.key)) { checked ->
                        if (checked) selected.add(utxo.key) else selected.remove(utxo.key)
                        render()
                    })
                }
            } else {
                for (utxo in visibleUtxos) {
                    layoutList.addView(makeUtxoRow(utxo, frozenState[utxo.key] == true) { frozen ->
                        frozenState[utxo.key] = frozen
                        viewModel.toggleUtxoFrozen(utxo.txid, utxo.vout, frozen)
                        render()
                    })
                }
            }
            if (selectionMode) {
                val totalSats = visibleUtxos.filter { selected.contains(it.key) }.sumOf { it.valueSats }
                btnClose.text = "Confirmar seleção (%d · %,d sat)".format(selected.size, totalSats)
            }
        }
        render()

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .create()

        btnClose.setOnClickListener {
            if (selectionMode) onSelectionConfirmed?.invoke(selected)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun makeUtxoSelectableRow(utxo: UtxoRow, checked: Boolean, onToggle: (Boolean) -> Unit): View {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }

        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        val checkbox = CheckBox(requireContext()).apply {
            isChecked  = checked
            buttonTintList = ContextCompat.getColorStateList(requireContext(), R.color.bitcoin_orange)
            isClickable = false // o toque é tratado pelo container inteiro
        }

        val tvValue = TextView(requireContext()).apply {
            text      = "%,d sat".format(utxo.valueSats)
            textSize  = 13f
            typeface  = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), R.color.bitcoin_orange))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStatus = TextView(requireContext()).apply {
            text = if (utxo.confirmed) "✔ confirmado" else "⏳ mempool"
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(),
                if (utxo.confirmed) R.color.green_status else R.color.gb_border_soft))
        }

        headerRow.addView(checkbox)
        headerRow.addView(tvValue)
        headerRow.addView(tvStatus)

        // chain == -1 é o sinal de "UTXO Silent Payments" (ver
        // WalletViewModel.getUtxoList) — não vem de um endereço
        // derivado/chain/index normal, mostra um rótulo próprio em vez de
        // truncar o texto placeholder do address como se fosse um endereço.
        val sourceLabel = if (utxo.chain == -1) {
            "🔒 Silent Payments"
        } else {
            val chainLabel = if (utxo.chain == 0) "recebimento" else "troco"
            "${utxo.address.take(12)}…${utxo.address.takeLast(6)} ($chainLabel #${utxo.index})"
        }
        val tvSource = TextView(requireContext()).apply {
            text     = sourceLabel
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        container.addView(headerRow)
        container.addView(tvSource)
        container.setOnClickListener { onToggle(!checked) }

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            )
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
        }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(container)
            addView(divider)
        }
    }

    private fun makeUtxoRow(utxo: UtxoRow, frozen: Boolean, onToggle: (Boolean) -> Unit): View {
        val dp = resources.displayMetrics.density
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }

        val headerRow = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }

        val tvValue = TextView(requireContext()).apply {
            text      = "%,d sat".format(utxo.valueSats)
            textSize  = 13f
            typeface  = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), R.color.bitcoin_orange))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvStatus = TextView(requireContext()).apply {
            text = if (frozen) "🧊 congelado" else if (utxo.confirmed) "✔ confirmado" else "⏳ mempool"
            textSize = 11f
            setTextColor(ContextCompat.getColor(requireContext(),
                if (frozen) R.color.bitcoin_orange
                else if (utxo.confirmed) R.color.green_status
                else R.color.gb_border_soft))
        }

        headerRow.addView(tvValue)
        headerRow.addView(tvStatus)

        // chain == -1 é o sinal de "UTXO Silent Payments" (ver
        // WalletViewModel.getUtxoList) — não vem de um endereço
        // derivado/chain/index normal, mostra um rótulo próprio em vez de
        // truncar o texto placeholder do address como se fosse um endereço.
        val sourceLabel = if (utxo.chain == -1) {
            "🔒 Silent Payments"
        } else {
            val chainLabel = if (utxo.chain == 0) "recebimento" else "troco"
            "${utxo.address.take(12)}…${utxo.address.takeLast(6)} ($chainLabel #${utxo.index})"
        }
        val tvSource = TextView(requireContext()).apply {
            text     = sourceLabel
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
            setPadding(0, (4 * dp).toInt(), 0, 0)
        }

        container.addView(headerRow)
        container.addView(tvSource)

        container.setOnClickListener { onToggle(!frozen) }
        if (frozen) container.alpha = 0.6f

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            )
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
        }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(container)
            addView(divider)
        }
    }

    private fun showSendDialog(prefillAddress: String? = null) {
        val dialogView   = layoutInflater.inflate(R.layout.dialog_send, null)
        val etAddress    = dialogView.findViewById<TextInputEditText>(R.id.et_address)
        val btnScanQr    = dialogView.findViewById<MaterialButton>(R.id.btn_scan_qr)
        val chipGroup    = dialogView.findViewById<ChipGroup>(R.id.chip_group_currency)
        val tilAmount    = dialogView.findViewById<TextInputLayout>(R.id.til_amount)
        val etAmount     = dialogView.findViewById<TextInputEditText>(R.id.et_amount)
        val tvConversion = dialogView.findViewById<TextView>(R.id.tv_conversion)
        val sliderFee    = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.slider_fee)
        val tvFeeRate    = dialogView.findViewById<TextView>(R.id.tv_fee_rate)
        val tvFeeTime    = dialogView.findViewById<TextView>(R.id.tv_fee_time_estimate)
        val cbSweep      = dialogView.findViewById<CheckBox>(R.id.cb_sweep)
        val btnSelectUtxos = dialogView.findViewById<MaterialButton>(R.id.btn_select_utxos)
        val tvUtxoSummary  = dialogView.findViewById<TextView>(R.id.tv_utxo_selection_summary)
        val rgSendMode   = dialogView.findViewById<RadioGroup>(R.id.rg_send_mode)
        val tvModeExplainer = dialogView.findViewById<TextView>(R.id.tv_send_mode_explainer)
        val progressSend = dialogView.findViewById<ProgressBar>(R.id.progress_send)
        val tvSendError  = dialogView.findViewById<TextView>(R.id.tv_send_error)
        val btnCancel    = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_send)
        val btnConfirm   = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_send)

        prefillAddress?.let { etAddress.setText(it) }

        var currentCurrency = "SATS"

        fun updateConversionText() {
            if (currentCurrency == "SATS") { tvConversion.visibility = View.GONE; return }
            val amountStr = etAmount.text?.toString()?.trim() ?: ""
            val amount    = amountStr.toDoubleOrNull() ?: run { tvConversion.visibility = View.GONE; return }
            val prices    = viewModel.getCurrentPrices()
            val sats: Long? = when (currentCurrency) {
                "BTC" -> (amount * 100_000_000).toLong()
                "USD" -> prices?.let { (amount / it.usd * 100_000_000).toLong() }
                "BRL" -> prices?.let { (amount / it.brl * 100_000_000).toLong() }
                else  -> null
            }
            if (sats == null) {
                tvConversion.text       = "Carregando cotação…"
                tvConversion.visibility = View.VISIBLE
            } else {
                tvConversion.text       = "≈ %,d sat".format(sats)
                tvConversion.visibility = View.VISIBLE
            }
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCurrency = when (checkedIds.firstOrNull()) {
                R.id.chip_btc -> "BTC"
                R.id.chip_usd -> "USD"
                R.id.chip_brl -> "BRL"
                else          -> "SATS"
            }
            tilAmount.hint = when (currentCurrency) {
                "BTC" -> "Valor em BTC"
                "USD" -> "Valor em USD ($)"
                "BRL" -> "Valor em BRL (R$)"
                else  -> "Valor em satoshis"
            }
            etAmount.inputType = if (currentCurrency == "SATS")
                InputType.TYPE_CLASS_NUMBER
            else
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            updateConversionText()
        }

        etAmount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateConversionText() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnScanQr.setOnClickListener {
            qrTargetAddressField = etAddress
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Aponte para o QR code do endereço Bitcoin")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }

        cbSweep.setOnCheckedChangeListener { _, checked ->
            tilAmount.isEnabled = !checked
            etAmount.isEnabled  = !checked
            if (checked) tvConversion.visibility = View.GONE
        }

        // Seleção manual de UTXOs (Fase B3): null = automático (CoinSelector
        // ou "tudo" no sweep). Quando o usuário escolhe manualmente, o envio
        // usa EXATAMENTE esses UTXOs — ver buildSignedTx() no ViewModel.
        var manualUtxoKeys: Set<String>? = null

        fun updateUtxoSummary() {
            val keys = manualUtxoKeys
            if (keys.isNullOrEmpty()) {
                tvUtxoSummary.visibility = View.GONE
            } else {
                val utxos = viewModel.getUtxoList().orEmpty()
                val totalSats = utxos.filter { it.key in keys }.sumOf { it.valueSats }
                tvUtxoSummary.text = "🪙 %d UTXO(s) selecionado(s) manualmente — %,d sat (toque no botão pra mudar)".format(keys.size, totalSats)
                tvUtxoSummary.visibility = View.VISIBLE
            }
        }

        btnSelectUtxos.setOnClickListener {
            showUtxoListDialog(
                selectionMode    = true,
                initialSelection = manualUtxoKeys ?: emptySet()
            ) { selected ->
                manualUtxoKeys = selected.ifEmpty { null }
                updateUtxoSummary()
            }
        }

        val feeEstimates = viewModel.getCurrentFeeEstimates()
        // Teto do slider = O DOBRO da taxa de prioridade alta atual da mempool
        // (pedido do Felipe — dá espaço pra pagar mais que o "rápido" em caso de
        // pressa). Sugestão/padrão = a taxa alta em si (não o teto). Os dois
        // arredondados pro múltiplo de 0.5 mais próximo — o Slider exige que o
        // passo (0.5) divida certinho o intervalo valueFrom..valueTo.
        val suggestedFeeRate = (kotlin.math.round(feeEstimates.fastest * 2) / 2.0).coerceAtLeast(0.5)
        val maxFeeRate       = (kotlin.math.ceil(feeEstimates.fastest * 2 * 2) / 2.0).coerceAtLeast(suggestedFeeRate)

        fun updateFeeLabels(rate: Double) {
            tvFeeRate.text = "%.1f sat/vB".format(rate)
            tvFeeTime.text = FeeTimeEstimator.estimate(feeEstimates.byBlockTarget, rate)
        }

        // Baixa o value pro mínimo ANTES de mudar valueTo — o Slider valida
        // value <= valueTo a cada set, e o value=10 do XML pode ser maior que
        // um valueTo novo bem baixo (mempool com pouco congestionamento).
        sliderFee.value   = sliderFee.valueFrom
        sliderFee.valueTo = maxFeeRate.toFloat()
        sliderFee.value   = suggestedFeeRate.toFloat()
        updateFeeLabels(suggestedFeeRate)

        sliderFee.addOnChangeListener { _, value, _ -> updateFeeLabels(value.toDouble()) }

        var currentSendMode: SendMode = SendMode.Internet
        rgSendMode.setOnCheckedChangeListener { _, checkedId ->
            currentSendMode = when (checkedId) {
                R.id.rb_mode_tor     -> SendMode.Tor
                R.id.rb_mode_bitchat -> SendMode.BitChat
                else                 -> SendMode.Internet
            }
            tvModeExplainer.setText(
                when (currentSendMode) {
                    is SendMode.Tor     -> R.string.send_mode_tor_explainer
                    is SendMode.BitChat -> R.string.send_mode_bitchat_explainer
                    else                -> R.string.send_mode_internet_explainer
                }
            )
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val destination = etAddress.text?.toString()?.trim() ?: ""
            val sweep       = cbSweep.isChecked
            val amountStr   = etAmount.text?.toString()?.trim()

            if (destination.isBlank()) {
                tvSendError.text       = "Informe o endereço destino"
                tvSendError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val amountSats: Long? = if (!sweep) {
                val raw = amountStr?.toDoubleOrNull() ?: run {
                    tvSendError.text       = "Informe o valor"
                    tvSendError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val prices = viewModel.getCurrentPrices()
                when (currentCurrency) {
                    "BTC"  -> (raw * 100_000_000).toLong()
                    "USD"  -> prices?.let { (raw / it.usd * 100_000_000).toLong() } ?: run {
                        tvSendError.text       = "Cotação indisponível. Aguarde ou use Sats."
                        tvSendError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    "BRL"  -> prices?.let { (raw / it.brl * 100_000_000).toLong() } ?: run {
                        tvSendError.text       = "Cotação indisponível. Aguarde ou use Sats."
                        tvSendError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    else   -> raw.toLong()
                }
            } else null

            progressSend.visibility = View.VISIBLE
            tvSendError.visibility  = View.GONE
            btnConfirm.isEnabled    = false
            btnCancel.isEnabled     = false

            viewModel.sendFunds(destination, amountSats, sweep, currentSendMode, sliderFee.value.toDouble(), manualUtxoKeys)

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.sendState.collectLatest { state ->
                    when (state) {
                        is SendState.Sending -> {
                            progressSend.visibility = View.VISIBLE
                            btnConfirm.isEnabled    = false
                        }
                        is SendState.PublishingToRelays -> {
                            progressSend.visibility = View.VISIBLE
                            tvSendError.text         = getString(R.string.publishing_to_relays)
                            tvSendError.setTextColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
                            tvSendError.visibility   = View.VISIBLE
                        }
                        is SendState.AwaitingRelayConfirmation -> {
                            progressSend.visibility = View.VISIBLE
                            tvSendError.text         = getString(R.string.awaiting_relay_confirmation)
                            tvSendError.setTextColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
                            tvSendError.visibility   = View.VISIBLE
                        }
                        is SendState.Success -> {
                            dialog.dismiss()
                            viewModel.resetSendState()
                            val message = if (state.confirmedByRelay) {
                                "✅ Enviado!\ntxid: ${state.txid.take(16)}…"
                            } else {
                                "📡 Publicado via Nostr — aguardando confirmação (pode levar alguns minutos)\ntxid: ${state.txid.take(16)}…"
                            }
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
                        }
                        is SendState.Error -> {
                            progressSend.visibility = View.GONE
                            btnConfirm.isEnabled    = true
                            btnCancel.isEnabled     = true
                            tvSendError.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                            tvSendError.text        = state.message
                            tvSendError.visibility  = View.VISIBLE
                            viewModel.resetSendState()
                        }
                        is SendState.Idle -> {}
                    }
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Bloqueia trocar/criar/restaurar carteira enquanto um envio estiver em
     *  andamento — proteção extra na UI além do mutex do ViewModel. */
    private fun canChangeActiveWallet(): Boolean {
        if (viewModel.sendState.value !is SendState.Idle) {
            Toast.makeText(requireContext(), "Aguarde o envio atual terminar.", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    /** Ponto único de entrada pro envio — decide entre o diálogo normal
     *  (assina e transmite local, carteira com seed) e o fluxo air-gapped
     *  (monta PSBT pra assinar noutro aparelho, carteira watch-only). */
    private fun openSend() {
        if (!canChangeActiveWallet()) return // reusa o mesmo guard de "envio em andamento"
        val state = viewModel.walletState.value as? WalletState.Loaded ?: return
        if (state.isWatchOnly) showAirGappedSendDialog() else showSendDialog()
    }

    /**
     * Lado watch-only do fluxo air-gapped (Fase C4): mesmo formulário do
     * envio normal (endereço/valor/taxa/sweep/seleção de UTXO — nenhum
     * desses depende de seed), mas o botão de confirmar monta um PSBT em
     * vez de assinar e transmitir. Modo de envio (Internet/BitChat) não se
     * aplica aqui, fica escondido.
     */
    private fun showAirGappedSendDialog() {
        val dialogView   = layoutInflater.inflate(R.layout.dialog_send, null)
        val etAddress    = dialogView.findViewById<TextInputEditText>(R.id.et_address)
        val btnScanQr    = dialogView.findViewById<MaterialButton>(R.id.btn_scan_qr)
        val chipGroup    = dialogView.findViewById<ChipGroup>(R.id.chip_group_currency)
        val tilAmount    = dialogView.findViewById<TextInputLayout>(R.id.til_amount)
        val etAmount     = dialogView.findViewById<TextInputEditText>(R.id.et_amount)
        val tvConversion = dialogView.findViewById<TextView>(R.id.tv_conversion)
        val sliderFee    = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.slider_fee)
        val tvFeeRate    = dialogView.findViewById<TextView>(R.id.tv_fee_rate)
        val tvFeeTime    = dialogView.findViewById<TextView>(R.id.tv_fee_time_estimate)
        val cbSweep      = dialogView.findViewById<CheckBox>(R.id.cb_sweep)
        val btnSelectUtxos = dialogView.findViewById<MaterialButton>(R.id.btn_select_utxos)
        val tvUtxoSummary  = dialogView.findViewById<TextView>(R.id.tv_utxo_selection_summary)
        val groupSendMode  = dialogView.findViewById<LinearLayout>(R.id.group_send_mode)
        val progressSend = dialogView.findViewById<ProgressBar>(R.id.progress_send)
        val tvSendError  = dialogView.findViewById<TextView>(R.id.tv_send_error)
        val btnCancel    = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_send)
        val btnConfirm   = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_send)

        groupSendMode.visibility = View.GONE
        btnConfirm.text = "Gerar PSBT"

        var currentCurrency = "SATS"

        fun updateConversionText() {
            if (currentCurrency == "SATS") { tvConversion.visibility = View.GONE; return }
            val amountStr = etAmount.text?.toString()?.trim() ?: ""
            val amount    = amountStr.toDoubleOrNull() ?: run { tvConversion.visibility = View.GONE; return }
            val prices    = viewModel.getCurrentPrices()
            val sats: Long? = when (currentCurrency) {
                "BTC" -> (amount * 100_000_000).toLong()
                "USD" -> prices?.let { (amount / it.usd * 100_000_000).toLong() }
                "BRL" -> prices?.let { (amount / it.brl * 100_000_000).toLong() }
                else  -> null
            }
            if (sats == null) {
                tvConversion.text       = "Carregando cotação…"
                tvConversion.visibility = View.VISIBLE
            } else {
                tvConversion.text       = "≈ %,d sat".format(sats)
                tvConversion.visibility = View.VISIBLE
            }
        }

        chipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentCurrency = when (checkedIds.firstOrNull()) {
                R.id.chip_btc -> "BTC"
                R.id.chip_usd -> "USD"
                R.id.chip_brl -> "BRL"
                else          -> "SATS"
            }
            tilAmount.hint = when (currentCurrency) {
                "BTC" -> "Valor em BTC"
                "USD" -> "Valor em USD ($)"
                "BRL" -> "Valor em BRL (R$)"
                else  -> "Valor em satoshis"
            }
            etAmount.inputType = if (currentCurrency == "SATS")
                InputType.TYPE_CLASS_NUMBER
            else
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            updateConversionText()
        }

        etAmount.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateConversionText() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnScanQr.setOnClickListener {
            qrTargetAddressField = etAddress
            val options = ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setPrompt("Aponte para o QR code do endereço Bitcoin")
                setBeepEnabled(false)
                setOrientationLocked(false)
            }
            qrScanLauncher.launch(options)
        }

        cbSweep.setOnCheckedChangeListener { _, checked ->
            tilAmount.isEnabled = !checked
            etAmount.isEnabled  = !checked
            if (checked) tvConversion.visibility = View.GONE
        }

        var manualUtxoKeys: Set<String>? = null

        fun updateUtxoSummary() {
            val keys = manualUtxoKeys
            if (keys.isNullOrEmpty()) {
                tvUtxoSummary.visibility = View.GONE
            } else {
                val utxos = viewModel.getUtxoList().orEmpty()
                val totalSats = utxos.filter { it.key in keys }.sumOf { it.valueSats }
                tvUtxoSummary.text = "🪙 %d UTXO(s) selecionado(s) manualmente — %,d sat (toque no botão pra mudar)".format(keys.size, totalSats)
                tvUtxoSummary.visibility = View.VISIBLE
            }
        }

        btnSelectUtxos.setOnClickListener {
            showUtxoListDialog(
                selectionMode    = true,
                initialSelection = manualUtxoKeys ?: emptySet()
            ) { selected ->
                manualUtxoKeys = selected.ifEmpty { null }
                updateUtxoSummary()
            }
        }

        val feeEstimates = viewModel.getCurrentFeeEstimates()
        val suggestedFeeRate = (kotlin.math.round(feeEstimates.fastest * 2) / 2.0).coerceAtLeast(0.5)
        val maxFeeRate       = (kotlin.math.ceil(feeEstimates.fastest * 2 * 2) / 2.0).coerceAtLeast(suggestedFeeRate)

        fun updateFeeLabels(rate: Double) {
            tvFeeRate.text = "%.1f sat/vB".format(rate)
            tvFeeTime.text = FeeTimeEstimator.estimate(feeEstimates.byBlockTarget, rate)
        }

        sliderFee.value   = sliderFee.valueFrom
        sliderFee.valueTo = maxFeeRate.toFloat()
        sliderFee.value   = suggestedFeeRate.toFloat()
        updateFeeLabels(suggestedFeeRate)

        sliderFee.addOnChangeListener { _, value, _ -> updateFeeLabels(value.toDouble()) }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener {
            viewModel.resetAirGappedSendState()
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val destination = etAddress.text?.toString()?.trim() ?: ""
            val sweep       = cbSweep.isChecked
            val amountStr   = etAmount.text?.toString()?.trim()

            if (destination.isBlank()) {
                tvSendError.text       = "Informe o endereço destino"
                tvSendError.visibility = View.VISIBLE
                return@setOnClickListener
            }

            val amountSats: Long? = if (!sweep) {
                val raw = amountStr?.toDoubleOrNull() ?: run {
                    tvSendError.text       = "Informe o valor"
                    tvSendError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                val prices = viewModel.getCurrentPrices()
                when (currentCurrency) {
                    "BTC"  -> (raw * 100_000_000).toLong()
                    "USD"  -> prices?.let { (raw / it.usd * 100_000_000).toLong() } ?: run {
                        tvSendError.text       = "Cotação indisponível. Aguarde ou use Sats."
                        tvSendError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    "BRL"  -> prices?.let { (raw / it.brl * 100_000_000).toLong() } ?: run {
                        tvSendError.text       = "Cotação indisponível. Aguarde ou use Sats."
                        tvSendError.visibility = View.VISIBLE
                        return@setOnClickListener
                    }
                    else   -> raw.toLong()
                }
            } else null

            progressSend.visibility = View.VISIBLE
            tvSendError.visibility  = View.GONE
            btnConfirm.isEnabled    = false
            btnCancel.isEnabled     = false

            viewModel.prepareAirGappedSend(destination, amountSats, sweep, sliderFee.value.toDouble(), manualUtxoKeys)

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.airGappedSendState.collectLatest { state ->
                    when (state) {
                        is AirGappedSendState.Building -> {
                            progressSend.visibility = View.VISIBLE
                            btnConfirm.isEnabled    = false
                        }
                        is AirGappedSendState.Ready -> {
                            dialog.dismiss()
                            viewModel.resetAirGappedSendState()
                            showAirGappedPsbtDialog(state.psbt)
                        }
                        is AirGappedSendState.Error -> {
                            progressSend.visibility = View.GONE
                            btnConfirm.isEnabled    = true
                            btnCancel.isEnabled     = true
                            tvSendError.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                            tvSendError.text        = state.message
                            tvSendError.visibility  = View.VISIBLE
                            viewModel.resetAirGappedSendState()
                        }
                        is AirGappedSendState.Idle -> {}
                    }
                }
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Mostra o PSBT montado como QR + texto, com botão pra escanear a tx
     *  assinada de volta (Fase C5 — verifica o txid antes de transmitir). */
    private fun showAirGappedPsbtDialog(psbt: AirGappedPsbt) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_airgapped_psbt, null)
        val imgQr        = dialogView.findViewById<android.widget.ImageView>(R.id.img_qr_psbt)
        val tvExpected   = dialogView.findViewById<TextView>(R.id.tv_expected_txid)
        val btnScan      = dialogView.findViewById<MaterialButton>(R.id.btn_scan_signed_tx)
        val layoutPasteHex = dialogView.findViewById<LinearLayout>(R.id.layout_paste_signed_tx)
        val etHex        = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.et_signed_tx_hex)
        val btnConfirmHex = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_signed_tx_hex)
        val progress     = dialogView.findViewById<ProgressBar>(R.id.progress_airgapped)
        val tvResult     = dialogView.findViewById<TextView>(R.id.tv_airgapped_result)
        val btnClose     = dialogView.findViewById<MaterialButton>(R.id.btn_close_airgapped)
        val rgMode       = dialogView.findViewById<RadioGroup>(R.id.rg_airgapped_broadcast_mode)
        val tvModeExplainer = dialogView.findViewById<TextView>(R.id.tv_airgapped_mode_explainer)

        var broadcastMode: SendMode = SendMode.Internet
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            broadcastMode = when (checkedId) {
                R.id.rb_airgapped_mode_tor     -> SendMode.Tor
                R.id.rb_airgapped_mode_bitchat -> SendMode.BitChat
                else                            -> SendMode.Internet
            }
            tvModeExplainer.setText(
                when (broadcastMode) {
                    is SendMode.Tor     -> R.string.send_mode_tor_explainer
                    is SendMode.BitChat -> R.string.send_mode_bitchat_explainer
                    else                -> R.string.send_mode_internet_explainer
                }
            )
        }

        tvExpected.text = "txid esperado: ${psbt.expectedTxid}"

        try {
            val sizePx = resources.displayMetrics.density.let { (240 * it).toInt() }
            imgQr.setImageBitmap(generateQrBitmap(psbt.psbtBase64, sizePx))
        } catch (_: Exception) {
            imgQr.visibility = View.GONE
            tvResult.text = "PSBT grande demais pra um QR só (muitos inputs — tente selecionar menos UTXOs manualmente). Tamanho: ${psbt.psbtBase64.length} caracteres."
            tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
            tvResult.visibility = View.VISIBLE
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnScan.setOnClickListener {
            launchQrScan("Aponte para o QR da tx assinada") { scanned ->
                progress.visibility = View.VISIBLE
                tvResult.visibility = View.GONE
                btnScan.isEnabled = false
                btnConfirmHex.isEnabled = false
                viewModel.submitSignedAirGappedTx(scanned.trim(), psbt.expectedTxid, psbt.network, broadcastMode)
            }
        }

        btnConfirmHex.setOnClickListener {
            val hex = etHex.text?.toString()?.trim().orEmpty()
            if (hex.isEmpty()) {
                Toast.makeText(requireContext(), "Cole o texto da tx assinada antes de confirmar.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            progress.visibility = View.VISIBLE
            tvResult.visibility = View.GONE
            btnScan.isEnabled = false
            btnConfirmHex.isEnabled = false
            viewModel.submitSignedAirGappedTx(hex, psbt.expectedTxid, psbt.network, broadcastMode)
        }

        airGappedBroadcastJob?.cancel()
        airGappedBroadcastJob = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.airGappedBroadcastState.collectLatest { state ->
                when (state) {
                    is AirGappedBroadcastState.Verifying -> {
                        progress.visibility = View.VISIBLE
                    }
                    is AirGappedBroadcastState.Success -> {
                        progress.visibility = View.GONE
                        btnScan.visibility = View.GONE
                        layoutPasteHex.visibility = View.GONE
                        tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_status))
                        tvResult.text = "✅ Transmitida!\ntxid: ${state.txid.take(16)}…"
                        tvResult.visibility = View.VISIBLE
                        viewModel.resetAirGappedBroadcastState()
                    }
                    is AirGappedBroadcastState.Error -> {
                        progress.visibility = View.GONE
                        btnScan.isEnabled = true
                        btnConfirmHex.isEnabled = true
                        tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.error_red))
                        tvResult.text = state.message
                        tvResult.visibility = View.VISIBLE
                        viewModel.resetAirGappedBroadcastState()
                    }
                    is AirGappedBroadcastState.Idle -> {}
                }
            }
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }

    private fun showRenameWalletDialog(currentName: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_rename_wallet, null)
        val etName      = dialogView.findViewById<TextInputEditText>(R.id.et_rename_wallet)
        val btnCancel   = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_rename_wallet)
        val btnConfirm  = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_rename_wallet)
        etName.setText(currentName)

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val newName = etName.text?.toString()?.trim() ?: ""
            if (newName.isNotEmpty()) viewModel.renameActiveWallet(newName)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Configura node Electrum próprio (opcional) — na Mochila, seção REDE.
     *  [statusView] é atualizado direto ao salvar, sem esperar recomposição. */
    private fun showElectrumNodeDialog(statusView: TextView) {
        val context      = requireContext()
        val dialogView   = layoutInflater.inflate(R.layout.dialog_electrum_node, null)
        val cbEnabled    = dialogView.findViewById<CheckBox>(R.id.cb_electrum_enabled)
        val groupFields  = dialogView.findViewById<View>(R.id.group_electrum_fields)
        val etHost       = dialogView.findViewById<TextInputEditText>(R.id.et_electrum_host)
        val etPort       = dialogView.findViewById<TextInputEditText>(R.id.et_electrum_port)
        val cbTls        = dialogView.findViewById<CheckBox>(R.id.cb_electrum_tls)
        val tvTlsWarning = dialogView.findViewById<TextView>(R.id.tv_electrum_tls_warning)
        val cbCrossCheck = dialogView.findViewById<CheckBox>(R.id.cb_electrum_cross_check)
        val btnTest      = dialogView.findViewById<MaterialButton>(R.id.btn_test_electrum_connection)
        val tvTestResult = dialogView.findViewById<TextView>(R.id.tv_electrum_test_result)
        val tvError      = dialogView.findViewById<TextView>(R.id.tv_electrum_error)
        val btnCancel    = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_electrum_node)
        val btnConfirm   = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_electrum_node)

        cbEnabled.isChecked  = NodePrefs.isEnabled(context)
        etHost.setText(NodePrefs.getHost(context) ?: "")
        etPort.setText(NodePrefs.getPort(context).toString())
        cbTls.isChecked = NodePrefs.isTlsEnabled(context)
        cbCrossCheck.isChecked = NodePrefs.isCrossCheckEnabled(context)
        groupFields.visibility = if (cbEnabled.isChecked) View.VISIBLE else View.GONE
        tvTlsWarning.visibility = if (cbTls.isChecked) View.GONE else View.VISIBLE

        cbEnabled.setOnCheckedChangeListener { _, checked ->
            groupFields.visibility = if (checked) View.VISIBLE else View.GONE
            tvError.visibility = View.GONE
        }

        cbTls.setOnCheckedChangeListener { _, checked ->
            tvTlsWarning.visibility = if (checked) View.GONE else View.VISIBLE
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnTest.setOnClickListener {
            val host = etHost.text?.toString()?.trim() ?: ""
            val port = etPort.text?.toString()?.trim()?.toIntOrNull()
            if (host.isEmpty() || port == null) {
                tvTestResult.text          = "❌ Preencha host e porta antes de testar."
                tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                tvTestResult.visibility    = View.VISIBLE
                return@setOnClickListener
            }
            tvTestResult.text       = "🔄 Testando…"
            tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.gb_border_soft))
            tvTestResult.visibility = View.VISIBLE
            btnTest.isEnabled       = false

            lifecycleScope.launch {
                val reachable = withContext(Dispatchers.IO) {
                    try {
                        java.net.Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress(host, port), 4000)
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                btnTest.isEnabled = true
                if (reachable) {
                    tvTestResult.text = "✅ Conectou em $host:$port"
                    tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.green_status))
                } else {
                    tvTestResult.text = "❌ Não conseguiu conectar em $host:$port — confira se o aparelho está na mesma rede do node e se ele está rodando."
                    tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                }
            }
        }

        btnConfirm.setOnClickListener {
            val enabled = cbEnabled.isChecked
            if (enabled) {
                val host = etHost.text?.toString()?.trim() ?: ""
                val port = etPort.text?.toString()?.trim()?.toIntOrNull()
                if (host.isEmpty()) {
                    tvError.text       = "Informe o host do node."
                    tvError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                if (port == null || port !in 1..65535) {
                    tvError.text       = "Porta inválida — informe um número de 1 a 65535."
                    tvError.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                NodePrefs.save(context, enabled = true, host = host, port = port, useTls = cbTls.isChecked, crossCheck = cbCrossCheck.isChecked)
            } else {
                NodePrefs.disable(context)
            }
            statusView.text = NodePrefs.statusLabel(context)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Atualiza o texto de status da seção Silent Payments (Mochila) — host
     *  configurado, altura já escaneada e quantos UTXOs SP já foram
     *  achados. Chamado ao abrir a Mochila, depois de configurar o oracle,
     *  e depois de cada sync. */
    private fun updateSpOracleStatus(statusView: TextView) {
        val status = viewModel.getSilentPaymentSyncStatus()
        statusView.text = when {
            status == null -> "…"
            status.isWatchOnly -> "⚠️ Carteira watch-only — scan Silent Payments exige a carteira com a seed neste aparelho (fase futura)."
            status.oracleHost == null -> "⚠️ Nenhum oracle configurado pra esta rede — configure um manualmente."
            else -> {
                val tipLabel = if (status.lastScanTipHeight > 0) status.lastScanTipHeight.toString() else "ainda não escaneado"
                "🔒 Oracle: ${status.oracleHost}${if (!status.oracleTlsEnabled) " (sem TLS)" else ""}\n" +
                    "Escaneado até altura $tipLabel · ${status.knownUtxoCount} UTXO(s) Silent Payments"
            }
        }
    }

    /** Configura o host do blindbit-oracle (Mochila, seção "Silent
     *  Payments") — mesmo padrão de [showElectrumNodeDialog], só que sem
     *  porta separada (a URL já inclui host+esquema) nem opção de
     *  desligar (sempre há um host, próprio ou o bootstrap público). */
    private fun showSpOracleDialog(statusView: TextView) {
        val context = requireContext()
        val status = viewModel.getSilentPaymentSyncStatus()
        if (status == null) {
            Toast.makeText(context, "Aguarde a carteira terminar de carregar.", Toast.LENGTH_SHORT).show()
            return
        }

        val dialogView   = layoutInflater.inflate(R.layout.dialog_sp_oracle, null)
        val etHost       = dialogView.findViewById<TextInputEditText>(R.id.et_sp_oracle_host)
        val cbTls        = dialogView.findViewById<CheckBox>(R.id.cb_sp_oracle_tls)
        val tvTlsWarning = dialogView.findViewById<TextView>(R.id.tv_sp_oracle_tls_warning)
        val tvError      = dialogView.findViewById<TextView>(R.id.tv_sp_oracle_error)
        val btnCancel    = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_sp_oracle)
        val btnConfirm   = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_sp_oracle)

        etHost.setText(status.oracleHost ?: "")
        cbTls.isChecked = status.oracleTlsEnabled
        tvTlsWarning.visibility = if (cbTls.isChecked) View.GONE else View.VISIBLE
        cbTls.setOnCheckedChangeListener { _, checked ->
            tvTlsWarning.visibility = if (checked) View.GONE else View.VISIBLE
        }

        val dialog = AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val host = etHost.text?.toString()?.trim() ?: ""
            if (host.isEmpty()) {
                tvError.text       = "Informe o host do oracle."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            BlindBitOraclePrefs.save(context, host = host, useTls = cbTls.isChecked)
            updateSpOracleStatus(statusView)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    /** Dispara viewModel.syncSilentPayments() (Mochila, botão "Sincronizar
     *  agora") — desabilita o botão enquanto roda (pode levar um tempo,
     *  ~2000 blocos no primeiro scan) pra evitar toque duplo disparando
     *  dois syncs concorrentes. */
    private fun triggerSilentPaymentsSync(statusView: TextView, button: MaterialButton) {
        button.isEnabled = false
        val originalText = button.text
        button.text = "🔄 Sincronizando…"
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { viewModel.syncSilentPayments() }
                updateSpOracleStatus(statusView)
                val msg = if (result.confirmedUtxos.isEmpty())
                    "Sincronizado — nenhum pagamento Silent Payments novo encontrado."
                else
                    "✅ ${result.confirmedUtxos.size} pagamento(s) Silent Payments encontrado(s)!"
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Erro ao sincronizar Silent Payments: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                button.isEnabled = true
                button.text = originalText
            }
        }
    }

    /** Configura o host:porta do proxy SOCKS do Orbot (Mochila, seção
     *  TOR) — usado só quando o usuário escolhe o modo "Tor" na hora de
     *  enviar; não tem toggle de habilitar/desabilitar porque não é um
     *  modo padrão, só uma opção no diálogo de envio. */
    private fun showTorConfigDialog(statusView: TextView) {
        val context      = requireContext()
        val dialogView   = layoutInflater.inflate(R.layout.dialog_tor_config, null)
        val etHost       = dialogView.findViewById<TextInputEditText>(R.id.et_tor_host)
        val etPort       = dialogView.findViewById<TextInputEditText>(R.id.et_tor_port)
        val btnTest      = dialogView.findViewById<MaterialButton>(R.id.btn_test_tor_connection)
        val tvTestResult = dialogView.findViewById<TextView>(R.id.tv_tor_test_result)
        val tvError      = dialogView.findViewById<TextView>(R.id.tv_tor_error)
        val btnCancel    = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_tor_config)
        val btnConfirm   = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_tor_config)

        etHost.setText(TorPrefs.getHost(context))
        etPort.setText(TorPrefs.getPort(context).toString())

        val dialog = AlertDialog.Builder(context, R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnTest.setOnClickListener {
            val host = etHost.text?.toString()?.trim() ?: ""
            val port = etPort.text?.toString()?.trim()?.toIntOrNull()
            if (host.isEmpty() || port == null) {
                tvTestResult.text          = "❌ Preencha host e porta antes de testar."
                tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                tvTestResult.visibility    = View.VISIBLE
                return@setOnClickListener
            }
            tvTestResult.text       = "🔄 Testando…"
            tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.gb_border_soft))
            tvTestResult.visibility = View.VISIBLE
            btnTest.isEnabled       = false

            lifecycleScope.launch {
                // Só confirma que ALGO está escutando nessa porta (TCP connect
                // simples) — não valida que é de fato um proxy SOCKS5/Orbot
                // funcional, mesma limitação do "Testar conexão" do Electrum.
                val reachable = withContext(Dispatchers.IO) {
                    try {
                        java.net.Socket().use { socket ->
                            socket.connect(java.net.InetSocketAddress(host, port), 4000)
                        }
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                btnTest.isEnabled = true
                if (reachable) {
                    tvTestResult.text = "✅ Alguma coisa está escutando em $host:$port"
                    tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.green_status))
                } else {
                    tvTestResult.text = "❌ Não conseguiu conectar em $host:$port — confira se o Orbot está instalado e rodando, com o proxy SOCKS habilitado."
                    tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.error_red))
                }
            }
        }

        btnConfirm.setOnClickListener {
            val host = etHost.text?.toString()?.trim() ?: ""
            val port = etPort.text?.toString()?.trim()?.toIntOrNull()
            if (host.isEmpty()) {
                tvError.text       = "Informe o host do proxy."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            if (port == null || port !in 1..65535) {
                tvError.text       = "Porta inválida — informe um número de 1 a 65535."
                tvError.visibility = View.VISIBLE
                return@setOnClickListener
            }
            TorPrefs.save(context, host = host, port = port)
            statusView.text = TorPrefs.statusLabel(context)
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showSwitchWalletDialog() {
        val current = (viewModel.walletState.value as? WalletState.Loaded)?.fingerprint
        val wallets = viewModel.listKnownWallets()
        if (wallets.size <= 1) return

        val dialogView = layoutInflater.inflate(R.layout.dialog_wallet_switcher, null)
        val chipGroup   = dialogView.findViewById<ChipGroup>(R.id.chip_group_wallets)
        val btnCancel   = dialogView.findViewById<MaterialButton>(R.id.btn_cancel_switch_wallet)
        val btnConfirm  = dialogView.findViewById<MaterialButton>(R.id.btn_confirm_switch_wallet)

        val chipIdToFingerprint = mutableMapOf<Int, String>()
        wallets.forEach { (fingerprint, displayName, isWatchOnly) ->
            val chip = com.google.android.material.chip.Chip(requireContext()).apply {
                id = View.generateViewId()
                text = displayName
                isCheckable = true
                setChipBackgroundColorResource(android.R.color.transparent)
                setEnsureMinTouchTargetSize(false)
                // Cada carteira representada por uma pokébola — colorida (cores
                // próprias, sem tint) quando tem a chave (seed), cinza quando é
                // watch-only (só xpub, sem poder assinar sozinha).
                chipIcon = ContextCompat.getDrawable(requireContext(), R.drawable.ic_pokeball)
                isChipIconVisible = true
                chipIconTint = if (isWatchOnly)
                    ContextCompat.getColorStateList(requireContext(), R.color.pokeball_gray)
                else null
                chipIconSize = 22f * resources.displayMetrics.density
            }
            chipGroup.addView(chip)
            chipIdToFingerprint[chip.id] = fingerprint
            if (fingerprint == current) chipGroup.check(chip.id)
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnConfirm.setOnClickListener {
            val selectedFingerprint = chipIdToFingerprint[chipGroup.checkedChipId]
            dialog.dismiss()
            if (selectedFingerprint != null && selectedFingerprint != current) {
                viewModel.switchWallet(selectedFingerprint)
            }
        }

        dialog.show()
        dialog.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
    }

    private fun showAddWalletChoiceDialog() {
        AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setTitle("➕ Nova carteira")
            .setItems(arrayOf("Criar carteira nova", "Restaurar carteira existente", "👁 Importar watch-only (xpub)")) { _, which ->
                when (which) {
                    0 -> WalletCreationFlow.showPassphraseChoiceDialog(this, viewModel)
                    1 -> WalletCreationFlow.showRestoreDialog(this, viewModel) { prompt, onResult -> launchQrScan(prompt, onResult) }
                    else -> WalletCreationFlow.showWatchOnlyImportDialog(this, viewModel)
                }
            }
            .show()
    }

    private fun confirmForget() {
        if (!canChangeActiveWallet()) return
        val hasOtherWallets = viewModel.listKnownWallets().size > 1
        val consequence = if (hasOtherWallets)
            "As outras carteiras continuam intactas — o app troca pra uma delas automaticamente."
        else
            "Essa é a última carteira — o app volta pra tela de criação."

        AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setTitle("⚠️ Esquecer esta carteira")
            .setMessage(
                "Isso apaga o wallet.json local desta carteira (só ela, não as outras).\n\n" +
                "Seus fundos NÃO serão perdidos, mas você precisará do mnemonic + Pokémon " +
                "para recuperar esta carteira depois.\n\n$consequence\n\nTem certeza absoluta?"
            )
            .setPositiveButton("Esquecer") { _, _ -> viewModel.forgetWallet() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun makeTxRow(tx: WalletTx): View {
        val dp = resources.displayMetrics.density
        val row = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(0, (10 * dp).toInt(), 0, (10 * dp).toInt())
        }

        val isReceived   = tx.netSats >= 0
        val amountColor  = if (isReceived) R.color.green_status else R.color.error_red
        val amountPrefix = if (isReceived) "+" else ""

        val tvAmount = TextView(requireContext()).apply {
            text      = "%s%,d sat".format(amountPrefix, tx.netSats)
            textSize  = 13f
            typeface  = Typeface.MONOSPACE
            setTextColor(ContextCompat.getColor(requireContext(), amountColor))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val txTimeFmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        val statusText = if (tx.confirmed && tx.blockTime != null)
            txTimeFmt.format(Date(tx.blockTime * 1000))
        else
            "⏳ pendente"

        val tvStatus = TextView(requireContext()).apply {
            text      = statusText
            textSize  = 11f
            setTextColor(ContextCompat.getColor(requireContext(),
                if (tx.confirmed) R.color.gb_border_soft else R.color.bitcoin_orange))
        }

        row.addView(tvAmount)
        row.addView(tvStatus)

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.setMargins(0, 0, 0, 0) }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
        }

        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(divider)
        }
    }

    private fun makeTxPlaceholder(): View = TextView(requireContext()).apply {
        text      = "Nenhuma transação encontrada"
        textSize  = 13f
        setTextColor(ContextCompat.getColor(requireContext(), R.color.gb_border_soft))
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0,
            (8 * resources.displayMetrics.density).toInt())
    }
}
