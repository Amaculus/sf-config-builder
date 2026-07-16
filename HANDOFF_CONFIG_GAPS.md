# Handoff: gaps de `sfconfig` que bloquean un crawl real (caso Tour Experto)

**Fecha:** 2026-07-15 · **Para:** la sesión que mantenga `C:/Users/Antonio/sf-config-builder`
**Origen:** auditoría Tour Experto (`C:/Users/Antonio/SEO Audits/`), preparando el crawl que alimenta SF-1..10 + CW-6..10 (ver `SEO Audits/SF_CRAWL_SETUP_HANDOFF.md`).

---

## ✅ RESUELTO 2026-07-15 (misma tarde) — todos los items, verificados por round-trip a disco

| Item | Fix | Evidencia |
|---|---|---|
| Blocker 1 (header custom) | `mCustomHttpHeadersConfig.mHttpHeaders` permitido en `save()` + `add_http_header(name, value)` / `remove_http_header(name)` en Python. Acepta `{"name","value"}`, `"Name: Value"` y la forma cruda `HttpHeader [...]`; ops `set/append/prepend/clear/remove` | Script de verificación del handoff: **PASSED** — 5 headers (los 4 del base intactos + `X-SEO-Audit`) tras save→reload |
| Blocker 2 (UA custom) | La hipótesis era correcta: con `mIsSeoSpider=True` SF restaura el UA default al deserializar. `mIsSeoSpider` y `mRobotsUserAgent` ahora permitidos; helper `set_user_agent(ua, robots_ua=None)` setea los tres | UA persiste el round-trip: `'AuditLabs-SEO-Crawler/1.0 (+https://auditlabs.co)'` |
| `mPreset` fantasma | Removido del ALLOWLIST (no existe en SF 22.2) | — |
| Gaps secundarios | `mAjaxTimeoutMillis` y `mCrawlHreflang` permitidos — **los campos SÍ existen en 22.2** y persisten (verificado: 10000 / True tras round-trip) | — |
| Errores de enum | Ahora incluyen las opciones válidas: `Invalid rendering mode: STATIC (valid: HTML, JAVASCRIPT)` + `enumOptions` en details | — |
| Bug de docs (STATIC) | Corregido en `SF_CRAWL_SETUP_HANDOFF.md` y en el skill `seo-crawl-analysis` (que ahora también documenta los helpers nuevos) | — |

`_build_config.py` actualizado para usar los helpers: **`LISTO PARA CRAWLEAR: js=SI | static=SI`** con el base default — ya no hace falta el paso GUI. Tests del repo: 71 passed. Jar recompilado con el JDK que trae SF (`jre/bin/javac.exe` — es un JDK completo, no hace falta instalar nada).

**Queda para la sesión del crawl (sin bloqueos):** Task Scheduler para la ventana 2-5 AM ART + smoke test JS de 300-500 URLs para decidir JS vs HTML (medir throughput real y si el ISR devuelve 200 o 202).

---

---

## ✅ GAP NUEVO — RESUELTO 2026-07-15 (misma noche, antes de la ventana)

Los 4 campos están en el ALLOWLIST y verificados por round-trip a disco (el script de verificación de abajo: **PASSED**; suite 71 passed; jar recompilado):

- `mInteralURLConfig.mSearchAllSubdomains` (typo de SF respetado tal cual, con comentario en el código para que nadie lo "corrija") + property `config.crawl_all_subdomains = True`
- `mInteralURLConfig.mCrawlOutsideStartFolder` (el bonus)
- `mCrawlConfig.mCrawlImages` / `mStoreImages` + properties `config.crawl_images` / `config.store_images` (mismo trato que CSS/JS — chau excludes por regex)

Para el config de esta madrugada: `crawl_all_subdomains=True` + `crawl_images=False` + `store_images=False` y el blog entra al scope desde el crawl #1 → baseline de SF-9 limpio.

---

## 🆕 GAP NUEVO (2026-07-15, post-fix) — no se puede incluir un subdominio en el scope

**Caso:** Tour Experto necesita crawlear **`blog.tourexperto.com`** junto con `tourexperto.com` (la property de GSC es `sc-domain:`, o sea cubre subdominios → si el crawl no entra al blog, **SF-3 marca cada URL del blog como "Google la conoce, el sitio no la crawlea"**: un artefacto, no un hallazgo).

**El campo es:**
```
mInteralURLConfig.mSearchAllSubdomains = False   (boolean, editable=False)   <-- el "Crawl All Subdomains" de SF
```
⚠️ **Ojo con el typo de SF:** el grupo se llama `mInteralURLConfig` (falta la "n" de "Internal"). Es de SF, no nuestro — pero hay que respetarlo tal cual o el path no matchea.

**Está `editable=False` y fuera del ALLOWLIST** → `save()` lo rechaza.

**Por qué los include patterns NO alcanzan:** `mCrawlConfig.mIncludePatterns` sí está permitido, pero en SF los includes sólo filtran URLs *dentro* del scope; **no cambian la clasificación interno/externo**. Sin `mSearchAllSubdomains=True`, un link a otro subdominio se trata como **externo** (se toca 1 nivel, no se crawlea en profundidad). O sea: no hay workaround por includes.

**Ask:** permitir `mInteralURLConfig.mSearchAllSubdomains` (y evaluar el vecino `mInteralURLConfig.mCrawlOutsideStartFolder`, hoy también `editable=False`, útil para crawls seedeados en subcarpeta). Helper opcional: `config.crawl_all_subdomains(True)`.

### Gap hermano: `mCrawlConfig.mCrawlImages` / `mStoreImages`

```
mCrawlConfig.mCrawlImages = True   (boolean, editable=False)   <-- fuera del ALLOWLIST
mCrawlConfig.mStoreImages = True   (boolean, editable=False)
```
**Por qué importa (medido, no teórico):** con rendering activo SF crawlea los recursos **como URLs** y se comen el budget. En el mini crawl de Tour Experto: **1 sola página HTML real de 25 URLs** — el resto CSS/JS/svg/webp/avif/woff2. En un crawl con `mMaxUrls`, eso lo vuelve inútil; en uno full, infla el universo y distorsiona SF-3/CW-8.

`mCrawlCSS` / `mCrawlJavaScript` **sí** están permitidos y apagarlos funcionó (pasó a **23 páginas HTML de 25**, y verifiqué que el contenido se sigue extrayendo: word_count 440-3.821). Pero **imágenes/fuentes no se pueden apagar** → hoy lo tapo con excludes por regex (`.*\.(jpg|png|webp|avif|svg|woff2?|...)$`, `/_ipx/`, `/_nuxt/`), que funciona pero es frágil y hay que repetirlo por cliente.

**Ask:** permitir `mCrawlImages` / `mStoreImages` (mismo trato que CSS/JS, que ya están en el ALLOWLIST).

**Verificación:**
```python
c = SFConfig.load(BASE); c.set("mInteralURLConfig.mSearchAllSubdomains", True); c.save(OUT)
assert SFConfig.load(OUT).get("mInteralURLConfig.mSearchAllSubdomains") is True   # round-trip a disco
```

*Mientras tanto:* el smoke test y el Task Scheduler no dependen de esto (van sobre el dominio principal). Sólo bloquea el **crawl full con blog incluido**.

---

*(Texto original del handoff abajo, para referencia.)*

## TL;DR

El WAF del cliente sólo deja pasar al crawler si manda **un User-Agent exacto** y **un header HTTP custom**. `sfconfig` **no puede escribir ninguno de los dos**:

| Requisito del cliente | Campo | Estado |
|---|---|---|
| UA `AuditLabs-SEO-Crawler/1.0 (+https://auditlabs.co)` | `mUserAgentConfig.mUserAgent` | **Está en el whitelist, `set()` no falla, pero NO persiste** — revierte al default |
| Header `X-SEO-Audit: AL-tourexperto-923b2ab91a8ace19` | `mCustomHttpHeadersConfig.mHttpHeaders` | **`save()` lo rechaza**: `Field not allowed` |

Sin esos dos, el crawl no pasa el WAF y **todo el pack SF/CW-6..10 se queda sin insumo**. Hoy la única salida es un paso manual en la GUI de SF.

---

## Blocker 1 — `mCustomHttpHeadersConfig.mHttpHeaders` no está permitido en `save()`

### Reproducción
```python
import sys; sys.path.insert(0, r"C:\Users\Antonio\sf-config-builder")
from sfconfig import SFConfig
c = SFConfig.load(r"C:\Users\Antonio\default.seospiderconfig")
h = list(c.get("mCustomHttpHeadersConfig.mHttpHeaders"))
h.append("HttpHeader [\r\n   mName=X-SEO-Audit\r\n   mValue=AL-tourexperto-923b2ab91a8ace19\r\n]")
c.set("mCustomHttpHeadersConfig.mHttpHeaders", h)   # <-- set() NO falla
c.save("out.seospiderconfig")                        # <-- save() SÍ falla
# sfconfig.exceptions.SFValidationError: Field not allowed: mCustomHttpHeadersConfig.mHttpHeaders
```

### Detalle importante (facilita el fix)
- `fields()` lo reporta como `type='list<string>', editable=False`.
- **El formato de serialización ya lo conocemos** (es un objeto Java aplanado):
  ```
  'HttpHeader [\r\n   mName=Accept\r\n   mValue=text/html,application/xhtml+xml,...\r\n]'
  'HttpHeader [\r\n   mName=Accept-Encoding\r\n   mValue=gzip\r\n]'
  'HttpHeader [\r\n   mName=Cache-Control\r\n   mValue=no-cache\r\n]'
  'HttpHeader [\r\n   mName=Pragma\r\n   mValue=no-cache\r\n]'
  ```
- **Dato clave y a favor:** los headers que ya vienen en el config base **SÍ sobreviven a `save()`** (verificado: entran 4, salen los mismos 4). O sea el builder **ya los serializa bien de vuelta**; lo único que falta es **permitir modificar la lista**.

### Ask
Agregar `mCustomHttpHeadersConfig.mHttpHeaders` al whitelist de `save()`, aceptando idealmente una forma amigable además de la cruda, p.ej.:
```python
config.add_http_header("X-SEO-Audit", "AL-tourexperto-923b2ab91a8ace19")   # helper
# o que set() acepte [{"name": "...", "value": "..."}] y serialice al formato HttpHeader [...]
```
Un `add_http_header(name, value)` que **appendee** (sin pisar Accept / Accept-Encoding / Cache-Control / Pragma) sería lo ideal — pisarlos rompe el crawl.

---

## Blocker 2 — el User-Agent custom no persiste

### Reproducción
```python
c = SFConfig.load(r"C:\Users\Antonio\default.seospiderconfig")
c.set("mUserAgentConfig.mUserAgent", "AuditLabs-SEO-Crawler/1.0 (+https://auditlabs.co)")
c.save(OUT)                       # save() NO falla (el campo SÍ está en allowedFields)
SFConfig.load(OUT).get("mUserAgentConfig.mUserAgent")
# -> 'Screaming Frog SEO Spider/22.2'   <-- se perdió, volvió al default
```

### Diagnóstico
El grupo completo:
```
mUserAgentConfig.mIsSeoSpider    = True                            (boolean, editable=False)  <-- sospechoso
mUserAgentConfig.mUserAgent      = 'Screaming Frog SEO Spider/22.2' (string, editable=True)
mUserAgentConfig.mRobotsUserAgent= 'Screaming Frog SEO Spider'      (string, editable=False)
```
- **Hipótesis:** con `mIsSeoSpider=True`, SF ignora/reescribe `mUserAgent` al reconstruir el config. Para que un UA custom tome, probablemente haya que poder poner **`mIsSeoSpider=False`** (hoy `editable=False`).
- `mUserAgentConfig.mPreset` **aparece en el `allowedFields` del validador pero NO existe en el config** (`save()` → `Field not found: mPreset`). Posible desalineación entre el whitelist del validador y el schema real de la versión SF 22.2.

### Ask
1. Permitir `mUserAgentConfig.mIsSeoSpider` (o lo que corresponda) para que el UA custom sobreviva el round-trip.
2. Revisar la inconsistencia de `mPreset` (está permitido pero no existe).
3. Idealmente un helper: `config.set_user_agent("AuditLabs-SEO-Crawler/1.0 (+https://auditlabs.co)")` que deje el config **realmente** con ese UA (test = round-trip, no sólo que `set()` no tire error).
4. Considerar también `mRobotsUserAgent` (hoy `editable=False`): para modelar bien a Googlebot conviene poder alinearlo.

> ⚠️ **Nota de método para quien lo arregle:** `set()` devuelve OK para campos que `save()` después rechaza, y `save()` acepta campos que **no persisten**. El único test válido es **guardar → recargar del disco → comparar**. Si el fix se valida sólo con `set()`/`get()` en memoria, va a dar falso verde.

---

## Bug de documentación — `rendering_mode = "STATIC"` no existe

El `seo-crawl-analysis` skill **y** `SEO Audits/SF_CRAWL_SETUP_HANDOFF.md` documentan:
```python
config.rendering_mode = "STATIC"   # <-- NO existe; save() -> SFValidationError: Invalid rendering mode: STATIC
```
El enum real (de `fields()`):
```
mCrawlConfig.mRenderingMode  type=enum  value='HTML'  enumOptions=['HTML', 'JAVASCRIPT']
```
→ El crawl "static" de **SF-10** se configura con **`rendering_mode = "HTML"`**, no `"STATIC"`. Vale corregir ambos docs (y el skill), porque hoy quien siga el handoff al pie **rompe el build del config estático**.

*(Sugerencia para el builder: cuando un enum falla, incluir `enumOptions` en el mensaje de error — la metadata ya la tiene y ahorraría el viaje de debug.)*

## Gaps secundarios (mismo whitelist, menor prioridad)

| Campo | Por qué lo queríamos | Estado |
|---|---|---|
| `mCrawlConfig.mAjaxTimeoutMillis` | Subir el render-wait — lección WorkflowMax: el schema JS-inyectado se pierde si expira. El `SF_CRAWL_SETUP_HANDOFF.md` lo pide explícitamente | `Field not allowed` (confirma lo que ya estaba en memoria) |
| `mCrawlConfig.mCrawlHreflang` | El handoff lo pide para clientes internacionales (TE = 9 locales) | `Field not allowed`. Mitigante: `mStoreHreflang=True` por default, así que las tabs de hreflang deberían poblarse igual — **a confirmar en smoke test** |

## Lo que SÍ funciona (para que no se rompa en el fix)

Round-trip verificado OK sobre `default.seospiderconfig`:
`rendering_mode` (JAVASCRIPT/STATIC) · `robots_mode` (RESPECT) · `mCrawlConfig.mMaxThreads` · `mPerformanceConfig.mUrlRequestsPerSecond` · **`mSpiderStructuredDataConfig.mExtractJsonLd` / `mExtractMicrodata`** (críticos: vienen en `False` por default y sin ellos SF-10 falla en silencio) · preservación de los 4 headers del base.

### `allowedFields` real que devuelve el validador (referencia)
```
mCrawlConfig.mMaxUrls, mMaxDepth, mMaxThreads, mRobotsTxtMode, mRespectCanonical,
mRenderingMode, mCrawlDelay, mCrawlCSS, mStoreCSS, mCrawlJavaScript, mStoreJavaScript,
mStoreOriginalHtml, mStoreRenderedHtml, mExtractHttpHeader, mExtractCookies, mInspectAccessibility,
mUserAgentConfig.mUserAgent, mUserAgentConfig.mPreset,
mContentConfig.mMinContentLength, mLanguageToolConfig.*, mDuplicateConfig.*,
mSpiderStructuredDataConfig.{mExtractJsonLd,mExtractMicrodata,mExtractRdfa,mGoogleValidation,mSchemaDotOrgValidation,mCaseSensitiveValidation},
mPerformanceConfig.{mLimitPerformance,mUrlRequestsPerSecond},
mExcludeManager.{mExcludePatterns,mExcludeUrls}, mCrawlConfig.mIncludePatterns, mCrawlConfig.mAllowedDomains,
extractions, mCustomExtractionConfig.extractions, custom_searches, mCustomSearchConfig.searches,
custom_javascript, mCustomJavaScriptConfig.javascript
```

## Cómo verificar el fix

```python
import sys; sys.path.insert(0, r"C:\Users\Antonio\sf-config-builder")
from sfconfig import SFConfig
OUT = r"C:\Users\Antonio\SEO Audits\crawls\tourexperto\_verify.seospiderconfig"
c = SFConfig.load(r"C:\Users\Antonio\default.seospiderconfig")
c.set("mUserAgentConfig.mUserAgent", "AuditLabs-SEO-Crawler/1.0 (+https://auditlabs.co)")
c.add_http_header("X-SEO-Audit", "AL-tourexperto-923b2ab91a8ace19")   # o la API que definan
c.save(OUT)

r = SFConfig.load(OUT)                      # <-- recargar del DISCO, no reusar el objeto
assert r.get("mUserAgentConfig.mUserAgent") == "AuditLabs-SEO-Crawler/1.0 (+https://auditlabs.co)"
hdrs = r.get("mCustomHttpHeadersConfig.mHttpHeaders")
assert any("X-SEO-Audit" in str(h) and "AL-tourexperto-923b2ab91a8ace19" in str(h) for h in hdrs)
assert len(hdrs) == 5   # los 4 originales + el nuevo, sin pisar
```
Script de referencia ya escrito: `SEO Audits/crawls/tourexperto/_build_config.py`.

---

## Contexto adicional: el conflicto JS vs ventana del cliente (no es un bug, es una restricción)

Para que quien planifique el crawl lo tenga a mano. El cliente **sólo permite crawlear entre las 2 y las 5 AM hora Argentina** (3h) y a **≤3 req/s con pocos threads**. Contra las **28.722 URLs** de TE:

| Modo | Tiempo | Noches |
|---|---|---|
| STATIC @ 3 req/s | 2,7 h | **0,9 — entra en una noche** |
| JS ~1,5 URL/s (optimista) | 5,3 h | 1,8 |
| JS ~1,0 URL/s (típico) | 8,0 h | 2,7 |
| JS ~0,6 URL/s (con render wait) | 13,3 h | 4,4 |

**El conflicto:** `SF_CRAWL_SETUP_HANDOFF.md` exige **JS rendering + full-site scope + "dejá que el crawl termine, no lo mates y relances"**. Con 3 horas, **JS full-site no entra en una noche** → o se pausa/reanuda varias noches (frágil, y el handoff advierte que los crawls partidos envenenan los joins de demanda), o se va a STATIC.

**Dato específico de TE que inclina la balanza:** el sitio es **Nuxt SSR**. Un crawl por curl **sin JS** ya obtuvo `has_jsonld=1` en el 100% de las páginas 200, `hreflang_count=10`, títulos, H1 y mediana de **2.667 palabras** → **el contenido está en el HTML crudo**, STATIC lo captura. El costo de ir a STATIC es perder **SF-10** (diff JS-vs-static), que en este sitio sería *interesante* por el tema del shell HTTP 202 del ISR.

**Recomendación abierta:** smoke-test de 300-500 URLs en JS dentro de una ventana real (una vez activo el whitelist) para medir throughput y si devuelve 200 o 202, y recién ahí decidir. Es lo que pide el propio handoff y la regla de "smoke-test antes del batch".

## Datos operativos del caso (por si hacen falta)
- UA exigido: `AuditLabs-SEO-Crawler/1.0 (+https://auditlabs.co)`
- Header exigido: `X-SEO-Audit: AL-tourexperto-923b2ab91a8ace19`
- Límites: 3 threads, ≤3 req/s, ventana 2-5 AM ART
- Sitio: 28.722 URLs (9 locales), Nuxt SSR + AWS CloudFront + WAF
