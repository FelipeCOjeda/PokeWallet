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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WalletFragment : Fragment() {

    private lateinit var viewModel: WalletViewModel

    private var qrTargetAddressField: TextInputEditText? = null

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { content ->
            val address = content.removePrefix("bitcoin:").substringBefore("?")
            qrTargetAddressField?.setText(address)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_wallet, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel = ViewModelProvider(requireActivity())[WalletViewModel::class.java]

        val layoutTxHistory  = view.findViewById<LinearLayout>(R.id.layout_tx_history)
        val tvNetworkBadge   = view.findViewById<TextView>(R.id.tv_network_badge)
        val tvBalanceSats    = view.findViewById<TextView>(R.id.tv_balance_sats)
        val tvBalanceBtc     = view.findViewById<TextView>(R.id.tv_balance_btc)
        val tvBalancePending = view.findViewById<TextView>(R.id.tv_balance_pending)
        val progressScan     = view.findViewById<ProgressBar>(R.id.progress_scan)
        val tvScanStatus     = view.findViewById<TextView>(R.id.tv_scan_status)
        val tvLastScan       = view.findViewById<TextView>(R.id.tv_last_scan)
        val btnReceive       = view.findViewById<MaterialButton>(R.id.btn_receive)
        val btnSend          = view.findViewById<MaterialButton>(R.id.btn_send)
        val cardBag          = view.findViewById<View>(R.id.card_bag)
        val tvWalletName     = view.findViewById<TextView>(R.id.tv_wallet_name)
        val btnForget        = view.findViewById<MaterialButton>(R.id.btn_forget)
        val cardError        = view.findViewById<View>(R.id.card_error)
        val tvError          = view.findViewById<TextView>(R.id.tv_error)
        val bottomNav        = view.findViewById<BottomNavigationView>(R.id.bottom_nav)

        bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_base -> {
                    cardBag.visibility = View.GONE
                    true
                }
                R.id.nav_receive -> {
                    val pair = viewModel.getReceiveAddress()
                    if (pair != null) showReceiveDialog(pair.first, pair.second)
                    false
                }
                R.id.nav_send -> {
                    showSendDialog()
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

        btnSend.setOnClickListener { showSendDialog() }
        btnForget.setOnClickListener { confirmForget() }

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

                        tvWalletName.text = "🔑 ${state.walletName}"
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

    private fun showSendDialog(prefillAddress: String? = null) {
        val dialogView   = layoutInflater.inflate(R.layout.dialog_send, null)
        val etAddress    = dialogView.findViewById<TextInputEditText>(R.id.et_address)
        val btnScanQr    = dialogView.findViewById<MaterialButton>(R.id.btn_scan_qr)
        val chipGroup    = dialogView.findViewById<ChipGroup>(R.id.chip_group_currency)
        val tilAmount    = dialogView.findViewById<TextInputLayout>(R.id.til_amount)
        val etAmount     = dialogView.findViewById<TextInputEditText>(R.id.et_amount)
        val tvConversion = dialogView.findViewById<TextView>(R.id.tv_conversion)
        val cbSweep      = dialogView.findViewById<CheckBox>(R.id.cb_sweep)
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

            viewModel.sendFunds(destination, amountSats, sweep)

            viewLifecycleOwner.lifecycleScope.launch {
                viewModel.sendState.collectLatest { state ->
                    when (state) {
                        is SendState.Sending -> {
                            progressSend.visibility = View.VISIBLE
                            btnConfirm.isEnabled    = false
                        }
                        is SendState.Success -> {
                            dialog.dismiss()
                            viewModel.resetSendState()
                            Toast.makeText(
                                requireContext(),
                                "✅ Enviado!\ntxid: ${state.txid.take(16)}…",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is SendState.Error -> {
                            progressSend.visibility = View.GONE
                            btnConfirm.isEnabled    = true
                            btnCancel.isEnabled     = true
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

    private fun confirmForget() {
        AlertDialog.Builder(requireContext(), R.style.Theme_PokéWallet_Dialog)
            .setTitle("⚠️ Esquecer wallet")
            .setMessage(
                "Isso apaga o wallet.json local.\n\n" +
                "Seus fundos NÃO serão perdidos, mas você precisará do mnemonic + Pokémon para recuperar.\n\n" +
                "Tem certeza absoluta?"
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
                if (tx.confirmed) R.color.text_secondary else R.color.bitcoin_orange))
        }

        row.addView(tvAmount)
        row.addView(tvStatus)

        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (1 * dp).toInt()
            ).also { it.setMargins(0, 0, 0, 0) }
            setBackgroundColor(ContextCompat.getColor(requireContext(), R.color.divider))
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
        setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0,
            (8 * resources.displayMetrics.density).toInt())
    }
}
