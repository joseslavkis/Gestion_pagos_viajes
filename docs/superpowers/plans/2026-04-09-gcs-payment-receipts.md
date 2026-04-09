# GCS Payment Receipts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Guardar los comprobantes de pago en Google Cloud Storage en vez de persistirlos como base64 en la base de datos, manteniendo la UI operativa.

**Architecture:** El backend expone una abstracción de storage para comprobantes. La implementación por defecto conserva el comportamiento inline para no romper entornos sin configurar, mientras que la implementación GCS sube el archivo a un bucket privado, persiste solo la key y resuelve URLs firmadas al serializar DTOs.

**Tech Stack:** Spring Boot 3, Java 21, Google Cloud Storage Java client, React, Vite, MockMvc, JUnit 5

---

### Task 1: Cubrir el nuevo flujo con tests

**Files:**
- Modify: `backend/src/test/java/com/agencia/pagos/controllers/PaymentRestControllerFreeAmountTest.java`

- [ ] **Step 1: Escribir el test que falle**

Agregar una prueba que registre un pago con archivo, mockee el storage de comprobantes y verifique que:

```java
.andExpect(jsonPath("$.fileKey").value("https://signed.example/receipts/test.jpg"));
```

y luego compruebe en repositorio que el `PaymentSubmission` persistido guarda:

```java
assertThat(saved.getFileKey()).isEqualTo("receipts/test.jpg");
```

- [ ] **Step 2: Ejecutar el test y confirmar falla**

Run: `./mvnw -Dtest=PaymentRestControllerFreeAmountTest test`

Expected: FAIL porque el servicio actual serializa base64 y no usa storage externo.

- [ ] **Step 3: Agregar una aserción de lectura**

Extender un test de lectura de pendientes/historial para que, cuando la entidad tenga una key de storage, la API responda con la URL resuelta por el storage.

- [ ] **Step 4: Ejecutar el test y confirmar falla**

Run: `./mvnw -Dtest=PaymentRestControllerTest test`

Expected: FAIL porque la API devuelve la key cruda.

### Task 2: Integrar storage de comprobantes

**Files:**
- Create: `backend/src/main/java/com/agencia/pagos/services/storage/PaymentAttachmentStorageService.java`
- Create: `backend/src/main/java/com/agencia/pagos/services/storage/InlinePaymentAttachmentStorageService.java`
- Create: `backend/src/main/java/com/agencia/pagos/services/storage/GcsPaymentAttachmentStorageService.java`
- Create: `backend/src/main/java/com/agencia/pagos/config/storage/PaymentAttachmentStorageProperties.java`
- Modify: `backend/src/main/java/com/agencia/pagos/PagosApplication.java`
- Modify: `backend/src/main/java/com/agencia/pagos/services/PaymentService.java`
- Modify: `backend/pom.xml`
- Modify: `backend/src/main/resources/application.properties`
- Modify: `backend/src/test/resources/application.properties`

- [ ] **Step 1: Crear la interfaz de storage**

Definir una interfaz con dos operaciones:

```java
String storeReceipt(MultipartFile file, Long tripId, Long userId, Long studentId);
String resolveFileReference(String storedValue);
```

- [ ] **Step 2: Implementar proveedor inline**

Mover la lógica actual de base64 a `InlinePaymentAttachmentStorageService` para conservar compatibilidad y tests locales.

- [ ] **Step 3: Implementar proveedor GCS**

Usar el cliente oficial de GCS para:

```java
storage.create(blobInfo, file.getBytes());
storage.signUrl(blobInfo, expirationMinutes, TimeUnit.MINUTES, Storage.SignUrlOption.withV4Signature());
```

Persistir solo la key tipo `receipts/trip-<id>/user-<id>/<uuid>.<ext>`.

- [ ] **Step 4: Reemplazar la lógica en PaymentService**

Quitar `extractFileKey(...)` como encoder inline y delegar en el servicio de storage tanto al guardar como al responder DTOs.

- [ ] **Step 5: Configurar propiedades**

Agregar propiedades:

```properties
app.storage.receipts.provider=${RECEIPTS_STORAGE_PROVIDER:inline}
app.storage.receipts.gcs.bucket=${GCS_RECEIPTS_BUCKET:}
app.storage.receipts.gcs.url-expiration-minutes=${GCS_SIGNED_URL_EXPIRATION_MINUTES:15}
app.storage.receipts.gcs.path-prefix=${GCS_RECEIPTS_PATH_PREFIX:receipts}
```

- [ ] **Step 6: Ejecutar tests backend**

Run: `./mvnw -Dtest=PaymentRestControllerFreeAmountTest,PaymentRestControllerTest test`

Expected: PASS

### Task 3: Ajustar la UI para previsualizar imágenes desde URL

**Files:**
- Modify: `frontend/src/features/payments/pages/PendingReviewPage.tsx`
- Modify: `frontend/src/features/payments/components/PaymentDrawer.tsx`

- [ ] **Step 1: Agregar helper de detección de imágenes**

Renderizar `<img>` cuando el comprobante sea `data:image` o una URL que termine en `.jpg`, `.jpeg`, `.png` o `.webp`.

- [ ] **Step 2: Ejecutar los tests de frontend afectados si existen**

Run: `npm test -- PaymentReviewPage` o el comando equivalente del repo si la suite ya cubre esta vista.

Expected: PASS o, si no existe un test puntual, documentar la ausencia y verificar build/lint.
