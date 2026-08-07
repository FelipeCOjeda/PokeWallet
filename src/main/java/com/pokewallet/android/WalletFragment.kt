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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
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
        val tvSeedBackup     = view.findViewById<TextView>(R.id.tv_seed_backup_status)
        val btnSignAirGapped = view.findViewById<MaterialButton>(R.id.btn_sign_airgapped_psbt)
        val btnForget        = view.findViewById<MaterialButton>(R.id.btn_forget)
        val btnThemeLight    = view.findViewById<MaterialButton>(R.id.btn_theme_light)
        val btnThemeDark     = view.findViewById<MaterialButton>(R.id.btn_theme_dark)
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
        btnSignAirGapped.setOnClickListener { startSignAirGappedPsbtFlow() }
        rowHomeSwitcher.setOnClickListener {
            if (viewModel.listKnownWallets().size <= 1) return@setOnClickListener
            if (!canChangeActiveWallet()) return@setOnClickListener
            showSwitchWalletDialog()
        }

        ThemePrefs.bindToggle(requireActivity(), btnThemeLight, btnThemeDark)

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
        dialogView.findViewById<TextView>(R.id.tv_address).text       = address
        dialogView.findViewById<TextView>(R.id.tv_address_index).text = "Índice de derivação: $index"

        val imgQr = dialogView.findViewById<android.widget.ImageView>(R.id.img_qr)
        try {
            val sizePx = resources.displayMetrics.density.let { (220 * it).toInt() }
            imgQr.setImageBitmap(generateQrBitmap(address, sizePx))
        } catch (_: Exception) {
            imgQr.visibility = View.GONE
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .create()

        dialogView.findViewById<MaterialButton>(R.id.btn_copy_address).setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Bitcoin address", address))
            Toast.makeText(requireContext(), "Endereço copiado!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
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

    /** Lado signer do fluxo air-gapped (Fase C4): escaneia o QR do PSBT
     *  montado pela carteira watch-only e assina com a seed local. */
    private fun startSignAirGappedPsbtFlow() {
        launchQrScan("Aponte para o QR do PSBT (tela \"Assine no outro aparelho\")") { scanned ->
            viewModel.signAirGappedPsbt(scanned)
        }

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

        val imgQr = dialogView.findViewById<android.widget.ImageView>(R.id.img_qr_signed_tx)
        try {
            val sizePx = resources.displayMetrics.density.let { (240 * it).toInt() }
            imgQr.setImageBitmap(generateQrBitmap(rawTxHex, sizePx))
        } catch (_: Exception) {
            imgQr.visibility = View.GONE
            Toast.makeText(requireContext(), "TX assinada grande demais pra um QR só (muitos inputs) — transmita manualmente.", Toast.LENGTH_LONG).show()
        }

        val dialog = AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setView(dialogView)
            .setCancelable(false)
            .create()

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

        val chainLabel = if (utxo.chain == 0) "recebimento" else "troco"
        val tvSource = TextView(requireContext()).apply {
            text     = "${utxo.address.take(12)}…${utxo.address.takeLast(6)} ($chainLabel #${utxo.index})"
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

        val chainLabel = if (utxo.chain == 0) "recebimento" else "troco"
        val tvSource = TextView(requireContext()).apply {
            text     = "${utxo.address.take(12)}…${utxo.address.takeLast(6)} ($chainLabel #${utxo.index})"
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
                R.id.rb_mode_bitchat -> SendMode.BitChat
                else                 -> SendMode.Internet
            }
            tvModeExplainer.setText(
                if (currentSendMode is SendMode.BitChat) R.string.send_mode_bitchat_explainer
                else R.string.send_mode_internet_explainer
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
        val progress     = dialogView.findViewById<ProgressBar>(R.id.progress_airgapped)
        val tvResult     = dialogView.findViewById<TextView>(R.id.tv_airgapped_result)
        val btnClose     = dialogView.findViewById<MaterialButton>(R.id.btn_close_airgapped)
        val rgMode       = dialogView.findViewById<RadioGroup>(R.id.rg_airgapped_broadcast_mode)
        val tvModeExplainer = dialogView.findViewById<TextView>(R.id.tv_airgapped_mode_explainer)

        var broadcastMode: SendMode = SendMode.Internet
        rgMode.setOnCheckedChangeListener { _, checkedId ->
            broadcastMode = when (checkedId) {
                R.id.rb_airgapped_mode_bitchat -> SendMode.BitChat
                else                            -> SendMode.Internet
            }
            tvModeExplainer.setText(
                if (broadcastMode is SendMode.BitChat) R.string.send_mode_bitchat_explainer
                else R.string.send_mode_internet_explainer
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
                viewModel.submitSignedAirGappedTx(scanned.trim(), psbt.expectedTxid, psbt.network, broadcastMode)
            }
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
                        tvResult.setTextColor(ContextCompat.getColor(requireContext(), R.color.green_status))
                        tvResult.text = "✅ Transmitida!\ntxid: ${state.txid.take(16)}…"
                        tvResult.visibility = View.VISIBLE
                        viewModel.resetAirGappedBroadcastState()
                    }
                    is AirGappedBroadcastState.Error -> {
                        progress.visibility = View.GONE
                        btnScan.isEnabled = true
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
