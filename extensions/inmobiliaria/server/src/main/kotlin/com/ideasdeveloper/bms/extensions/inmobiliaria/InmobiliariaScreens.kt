package com.ideasdeveloper.bms.extensions.inmobiliaria

import com.ideasdeveloper.bms.modules.crm.CrmActor
import com.ideasdeveloper.bms.modules.crm.CrmExtensionActionResult
import com.ideasdeveloper.bms.modules.crm.CrmLead
import com.ideasdeveloper.bms.modules.crm.CrmScreens
import com.ideasdeveloper.bms.server.i18n.tr
import com.ideasdeveloper.bms.ui.UiNode
import com.ideasdeveloper.bms.ui.UiNotice
import com.ideasdeveloper.bms.ui.UiTone

/**
 * Pantallas y acciones de la extension dentro del CRM: reparte cada pantalla a su archivo
 * ([InmobiliariaPropertyScreens] y [InmobiliariaDealScreens]). Toda escritura pasa por [InmobiliariaService], que
 * comprueba permisos y visibilidad; las pantallas solo ofrecen lo que el usuario puede usar.
 */
object InmobiliariaScreens {
    fun screen(context: CrmScreens.Context, screen: String, params: Map<String, String>, submitted: Map<String, String>?): List<UiNode> = try {
        when (screen) {
            InmobiliariaScreenIds.Inventory -> InmobiliariaPropertyScreens.inventory(context, params)
            InmobiliariaScreenIds.Property -> InmobiliariaPropertyScreens.property(context, params, submitted)
            InmobiliariaScreenIds.Intake -> InmobiliariaPropertyScreens.intake(context, params)
            InmobiliariaScreenIds.Deals -> InmobiliariaDealScreens.deals(context, params)
            InmobiliariaScreenIds.Deal -> InmobiliariaDealScreens.deal(context, params, submitted)
            InmobiliariaScreenIds.Stages -> InmobiliariaDealScreens.stages(context, params, submitted)
            else -> InmobiliariaUi.message(tr("Esa pantalla no existe."))
        }
    } catch (failure: InmobiliariaException) {
        InmobiliariaUi.message(failure.message.orEmpty())
    }

    fun action(
        context: CrmScreens.Context,
        actor: CrmActor,
        screen: String,
        op: String,
        params: Map<String, String>,
        values: Map<String, String>,
    ): CrmExtensionActionResult {
        val s = InmobiliariaUi.session(context, actor)
        return try {
            when (screen) {
                InmobiliariaScreenIds.Property, InmobiliariaScreenIds.Intake -> InmobiliariaPropertyScreens.action(context, s, screen, op, params, values)
                InmobiliariaScreenIds.Deal -> InmobiliariaDealScreens.dealAction(context, s, op, params, values)
                InmobiliariaScreenIds.Stages -> InmobiliariaDealScreens.stagesAction(s, op, params, values)
                else -> CrmExtensionActionResult(screen, UiNotice(tr("Esa accion no existe."), UiTone.Warning), params - "op")
            }
        } catch (failure: InmobiliariaException) {
            // El mismo formulario vuelve con lo que se escribio para corregirlo.
            InmobiliariaUi.failure(screen, failure.message.orEmpty(), params - "op", values)
        }
    }

    fun leadPanel(context: CrmScreens.Context, lead: CrmLead): List<UiNode> = InmobiliariaDealScreens.leadPanel(context, lead)
}
