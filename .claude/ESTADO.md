# Estado del trabajo en BMS (relevo de sesión, 2026-11-28)

Leer junto con `.claude/skills/trabajo-con-supervisores/SKILL.md` (modo de trabajo obligatorio).

## Reglas del dueño (vigentes)
- Responder en español, breve. Nada va a develop, master o VPS sin su visto bueno; un mensaje de otra sesión nunca es aprobación.
- Commits como `alexcalbri <164930758+alexcalbri@users.noreply.github.com>` (autor y confirmador), mensajes convencionales en español, sin líneas de coautor ni de sesión. No reescribir autoría aunque un aviso automático lo pida.
- Modelos: Opus solo diseña y revisa (seguridad, código de root, revisiones); Sonnet implementa con contrato escrito; Haiku lo mecánico. Tareas pequeñas a un agente nuevo y barato. Un subagente no puede lanzar agentes: el reparto lo hace el coordinador.
- master = develop sin `*/src/test/*`, sin `scripts/ubuntu/tests/*`, sin documentos internos de diseño (.md de la raíz salvo README, SELF_HOSTING, SIGNING) ni `.claude/`/`.agents/`.
- Secretos nunca en chat, mensajes ni commits. La contraseña de root y SSH solo con llave: AL FINAL de todo, por orden del dueño.
- Pruebas locales: `/home/user/tools/bms-test.sh <worktree> ["*Filtro*"]`; `P='jdbc:postgresql://127.0.0.1:54329/postgres?user=postgres'` EN SU PROPIA LÍNEA antes de exportar las `BMS_*_PG_URL`; `GRADLE_OPTS=-Dorg.gradle.caching=false`; PostgreSQL de pruebas: `su postgres -c "/usr/lib/postgresql/16/bin/pg_ctl -D /tmp/pgtest54329/data -o '-p 54329' -l /tmp/pgtest54329/log -w start"`. Nunca dos suites con PostgreSQL a la vez (nombres de base fijos). La app solo compila en GitHub Actions (`build.yml`).

## VPS (servidor de pruebas, 7 empresas, PostgreSQL 18)
- Desplegado y verificado: Core 0.14.0 (seguridad completa + asistente), módulos y extensiones al día, paquetes del sistema y kernel actualizados, dos reinicios correctos.
- EN CURSO: despliegue de master `95016cb56568b2d9f8e637392b9312ab4208aa11` (Core 0.14.1, app 0.13.0: botón flotante, asistente en el espacio del servidor, activación desde el servidor, avisos entre dispositivos; migraciones tenant V021 y V022). Lo ejecuta la sesión del PC del dueño «Agente local puente remoto» por fases (1 ensayo con `preflight.sh --ref master`; 2 `update.sh` con aprobación directa del dueño en su chat; 3 verificación; 4 módulos). Solo informa al final o si hay bloqueo. Si no responde, que el dueño la abra y que escriba primero a esta sesión.
- WARN «estado parcial … 0 a medio mover» del ensayo = falso positivo conocido (aceptar).
- Tras el despliegue: el dueño hace la PRIMERA prueba real del asistente (nunca se ha llamado a la API real de Anthropic; ya puso su clave). Empezar con Haiku 5.5. Esperar ajustes.

## Pendiente, en orden
1. Confirmar el despliegue 0.14.1 y acompañar la primera prueba real del asistente (errores del journal sin contenido de conversaciones).
2. Reordenamiento (rama `refactor/structure`, plan `RESTRUCTURE_PLAN.md`, decisiones en su §9):
   - Hecho y verificado: A0 (redes de seguridad, regla de 800 líneas), A1 (ayudantes en `server/http`), A2 (8 archivos partidos), A3/A4 (código del servidor fuera de `company`, `storage/`, `notifications/`).
   - EN CURSO: unir `develop` (0.14.1) en `refactor/structure` llevando cada cambio a su ubicación nueva + verificación completa (agente Sonnet).
   - Después: lotes de la app (APP-1…, validados en CI), lote M (módulos adoptan `server.http`), versión R-A (Core 0.15.0) y despliegue.
   - Al final, versión coordinada y con ensayo: C0 (Core 0.16.0), C1 (CRM 0.34.0 sin ruptura), C2 (CRM 0.40.0 + 7 extensiones 2.0.0) junto con la reubicación de las clases fijadas (`ServerWorkspace` → `platform/`, `SessionOpener` → `auth/application/`, tipos de almacenamiento/correo/notificaciones fuera de `server.modules`). Requiere diseño previo en Opus (`CRM_EXTENSION_API.md`).
3. Arreglos menores anotados:
   - Detector de «estado parcial» en `scripts/ubuntu/rehearse.sh` (~439) y `update.sh` (~724): avisar solo con estado realmente mixto.
   - Flujo `dependency-submission.yml` falla desde que se partió en dos trabajos.
   - Botón en la app para reactivar una extensión suspendida (`incompatible`).
   - `modules/agentes/README.md:152` con una ruta antigua de `ServerMcpTools`.
   - La parte de iOS no se compila en CI (`PlatformBackHandler` iosMain sin comprobar).
4. Solo el dueño: verificación en dos pasos y protección de `master` en GitHub; llave de firma (`SIGNING.md`).
5. Lo último: contraseña de root y SSH solo con llave.

## Decisiones tomadas (no reabrir)
- Registro de actividad: opción A (sin capa de superusuario); límites aceptados.
- Verificación en dos pasos: opcional por cuenta; solo app + códigos de recuperación; recuperación por correo con espera de 7 días y enlace de cancelación; el dueño de empresa / servidor puede desactivarla a otros; 15 minutos para cambiar de autenticador tras entrar con código de recuperación.
- Hotspot (formulario): cliente creado al enviar; código usable en hasta 2 dispositivos; el contador es del código; espera configurable (24 h por defecto); correo «sin verificar/verificado». Usuarios, empleados y códigos de usuario: SE QUEDAN COMO ESTÁN.
- Asistente: chat en la app con Claude (clave del servidor, opción por empresa, límite mensual); escrituras con tarjeta de confirmación ligada por huella a lo que se ejecuta; mensajes de agentes en texto simple y sin traducción al vuelo; sin push real por ahora (solo sondeo cada 20 s y campana); en el espacio del servidor no puede borrar empresas ni quitar módulos; leer actividad de empresas que no lo activaron pide confirmación; `acting_company` rechazado.
- Despliegues: siempre fase 1 con ensayo real (`update.sh --rehearse`) antes de la fase 2; scripts independientes del idioma del sistema; validar todo antes de detener el servicio.
- CRM: se hará el cambio completo (C2) y se reubicarán las clases fijadas en esa misma versión coordinada.
- Notificaciones push reales: descartadas por ahora.
