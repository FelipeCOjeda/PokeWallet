package com.pokewallet.android

import android.bluetooth.BluetoothManager
import android.content.Context
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.util.UUID

/** Checagens de UI comuns às duas telas que usam o PokeSigner. */
object PokeSignerUi {

    /** Garante permissões BLE (pedindo se faltar) e Bluetooth ligado; só então chama [onReady]. */
    fun ensureReady(fragment: Fragment, onReady: () -> Unit) {
        val ctx = fragment.requireContext()
        val check = {
            val adapter = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter == null) {
                toast(ctx, "Este aparelho não tem Bluetooth.")
            } else if (!adapter.isEnabled) {
                toast(ctx, "Ligue o Bluetooth do celular para falar com o PokeSigner.")
            } else {
                onReady()
            }
        }
        if (PokeSignerClient.missingPermissions(ctx).isEmpty()) {
            check()
            return
        }
        // Registro sem lifecycle: pode ser feito a qualquer momento (o diálogo já está aberto).
        val registry = fragment.requireActivity().activityResultRegistry
        val key = "pokesigner_perm_" + UUID.randomUUID()
        var launcher: androidx.activity.result.ActivityResultLauncher<Array<String>>? = null
        launcher = registry.register(key, ActivityResultContracts.RequestMultiplePermissions()) { result ->
            launcher?.unregister()
            if (result.values.all { it }) check()
            else toast(ctx, "Sem a permissão de Bluetooth não dá para falar com o PokeSigner.")
        }
        launcher.launch(PokeSignerClient.requiredPermissions())
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
