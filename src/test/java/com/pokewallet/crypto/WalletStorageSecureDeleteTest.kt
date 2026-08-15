package com.pokewallet.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Cobre a correção do achado MÉDIO da auditoria (item 15): apagar o
 * wallet.json com File.delete() puro só desvincula o nome — os bytes
 * antigos podiam continuar recuperáveis por forense de armazenamento.
 * secureDelete() sobrescreve com zeros antes de apagar (best-effort, ver
 * doc da função pra limitação de wear-leveling em flash real).
 */
class WalletStorageSecureDeleteTest {

    @Test
    fun `overwriteWithZeros zera o conteudo do arquivo mantendo o tamanho`() {
        val tmp = File.createTempFile("wallet_secure_delete_test", ".json")
        val secretContent = "\"mnemonic\":\"abandon abandon abandon...\""
        tmp.writeText(secretContent)
        val originalLength = tmp.length()

        WalletStorage.overwriteWithZeros(tmp)

        assertEquals(originalLength, tmp.length())
        val bytesAfter = tmp.readBytes()
        assertTrue("todo byte deveria ser zero após overwriteWithZeros", bytesAfter.all { it == 0.toByte() })
        assertFalse("conteúdo original não pode sobrar de nenhum jeito", String(bytesAfter).contains("mnemonic"))
    }

    @Test
    fun `secureDelete sobrescreve e depois apaga o arquivo`() {
        val tmp = File.createTempFile("wallet_secure_delete_test2", ".json")
        tmp.writeText("segredo-que-nao-pode-sobrar-em-disco")

        val result = WalletStorage.secureDelete(tmp)

        assertTrue("secureDelete deveria retornar true pra um arquivo existente", result)
        assertFalse("arquivo não deveria mais existir depois de secureDelete", tmp.exists())
    }

    @Test
    fun `arquivo inexistente retorna true sem lancar excecao`() {
        val tmp = File.createTempFile("wallet_secure_delete_missing", ".json")
        tmp.delete()
        assertFalse(tmp.exists())

        val result = WalletStorage.secureDelete(tmp)

        assertTrue(result)
    }
}
