package com.ideasdeveloper.bms.modules.membresia

import com.ideasdeveloper.bms.server.activity.ActivityActor
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.server.modules.ModuleJob
import com.ideasdeveloper.bms.server.modules.ModuleJobContext
import com.ideasdeveloper.bms.server.modules.NotificationTarget
import com.ideasdeveloper.bms.server.modules.NotificationText
import com.ideasdeveloper.bms.server.modules.runGuarded
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import org.slf4j.LoggerFactory

/** Trabajo diario: cada paso va en su propio try/catch, con lotes acotados, y es idempotente. */
object MembresiaJobs {
    private val log = LoggerFactory.getLogger(MembresiaJobs::class.java)

    val daily = ModuleJob("membresia-diario", Duration.ofDays(1), dailyAt = LocalTime.of(7, 0)) { ctx -> run(ctx, LocalDate.now()) }

    /**
     * El Core solo programa los jobs de los modulos, no los de las extensiones: cada minuto se le da su turno a las fuentes
     * de importacion activadas en la empresa (leer un tablero, aplicar filas) con un presupuesto de 20 segundos.
     */
    val imports = ModuleJob("membresia-import", Duration.ofMinutes(1), longRunning = true, run = { ctx -> runImports(ctx) })

    fun runImports(ctx: ModuleJobContext) {
        for (source in MembresiaImportSources.enabled(ctx.tenant, ctx.companyCode)) {
            try {
                source.runPending(ctx, Duration.ofSeconds(20))
            } catch (cancellation: kotlin.coroutines.cancellation.CancellationException) {
                throw cancellation
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (failure: Exception) {
                log.error("Membresías {}: la fuente de importación {} falló", ctx.companyCode, source.sourceId, failure)
            }
        }
    }

    fun run(ctx: ModuleJobContext, today: LocalDate) {
        val tenant = ctx.tenant
        // 1. programada -> activa cuando ya empezo (lotes mientras haya mas, con tope).
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron activar las programadas") {
            repeatWhileFull { MembresiaRepository.activateStarted(tenant, today, BatchSize, ExpiryActor) }
        }
        // 2. activa -> vencida cuando su fin paso.
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron vencer las terminadas") {
            repeatWhileFull { MembresiaRepository.expireEnded(tenant, today, BatchSize, ExpiryActor).size }
        }
        // 3. aviso al equipo de las que estan por vencer.
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron avisar los vencimientos") {
            notifyExpiring(ctx, today)
        }
        // 4. pases WiFi del hotspot.
        runGuarded(log, "Membresías ${ctx.companyCode}: no se pudieron sincronizar los pases del hotspot") {
            MembresiaHotspot.syncAll(tenant, today)
        }
    }

    // Quien figura en el registro de actividad por lo que hacen las tareas programadas.
    private val ExpiryActor = ActivityActor.job("Vencimiento de membresías")
    private val ReminderActor = ActivityActor.job("Avisos de vencimiento de membresías")

    private const val BatchSize = 500
    private const val MaxBatches = 20

    // Repite el lote mientras devuelva uno completo, con un tope de iteraciones por corrida.
    private fun repeatWhileFull(batch: () -> Int) {
        repeat(MaxBatches) { if (batch() < BatchSize) return }
    }

    private fun notifyExpiring(ctx: ModuleJobContext, today: LocalDate) {
        val tenant = ctx.tenant
        val settings = MembresiaRepository.settings(tenant)
        val expiring = MembresiaRepository.expiringToNotify(tenant, today, settings.expiringDays)
        if (expiring.isEmpty()) return
        // Destinatarios base (dueño y quienes gestionan) y anfitriones activos: dos consultas en total, no una por membresia.
        val baseRecipients = MembresiaRepository.notificationRecipients(tenant, null)
        val activeHosts = MembresiaRepository.activeUserIds(tenant, expiring.mapNotNull { it.hostUserId }.distinct())
        for (membership in expiring) {
            runGuarded(log, "Membresías ${ctx.companyCode}: no se pudo avisar el vencimiento de ${membership.id}") {
                val host = membership.hostUserId
                val recipients = if (host != null && host in activeHosts) (baseRecipients + host).distinct().sorted() else baseRecipients
                recipients.forEach { userId ->
                    try {
                        ctx.notifier.notify(tenant, userId, NotificationTarget("miembro", mapOf("customer" to membership.customerId.toString()))) {
                            NotificationText(
                                tr("Membresía por vencer"),
                                tr("{0} · {1} vence el {2}", membership.customerName, membership.planName, membership.endsOn),
                            )
                        }
                    } catch (failure: Exception) {
                        log.warn("Membresías {}: no se pudo notificar a {}: {}", ctx.companyCode, userId, failure.message)
                    }
                }
                // Se marca aunque alguna notificacion haya fallado: no se repite cada dia.
                MembresiaRepository.markExpiringNotified(tenant, membership, today, ReminderActor)
            }
        }
    }
}
