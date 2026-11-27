package com.ideasdeveloper.bms.server.auth

import java.security.Provider
import java.security.Security
import java.security.spec.KeySpec
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.SecretKeyFactorySpi
import javax.crypto.spec.PBEKeySpec

/**
 * Counts the PBKDF2 iterations the code under test computes, without any hook in production code: a JCE
 * provider placed first answers PBKDF2WithHmacSHA256, counts the iterations of each request and delegates to
 * the JDK's own implementation.
 */
object Pbkdf2Counter {
    private val spent = ThreadLocal.withInitial { 0L }

    class CountingSpi : SecretKeyFactorySpi() {
        private val real = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", "SunJCE")
        override fun engineGenerateSecret(keySpec: KeySpec): SecretKey {
            (keySpec as? PBEKeySpec)?.let { spent.set(spent.get() + it.iterationCount) }
            return real.generateSecret(keySpec)
        }
        override fun engineGetKeySpec(key: SecretKey, keySpec: Class<*>): KeySpec = real.getKeySpec(key, keySpec)
        override fun engineTranslateKey(key: SecretKey): SecretKey = real.translateKey(key)
    }

    private object CountingProvider : Provider("BmsPbkdf2Counter", "1.0", "Counts PBKDF2 iterations in tests") {
        private fun readResolve(): Any = CountingProvider
        init {
            put("SecretKeyFactory.PBKDF2WithHmacSHA256", CountingSpi::class.java.name)
        }
    }

    /** Iterations [block] computed on this thread. */
    fun measure(block: () -> Unit): Long {
        if (Security.getProvider(CountingProvider.name) == null) Security.insertProviderAt(CountingProvider, 1)
        spent.set(0)
        try {
            block()
            return spent.get()
        } finally {
            Security.removeProvider(CountingProvider.name)
        }
    }
}
