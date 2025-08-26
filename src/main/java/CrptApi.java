import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.SerializedName;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe API client for the Честный знак system with request rate limiting.
 */
@Getter
public class CrptApi {
    // API endpoints
    private static final String PRODUCTION_BASE_URL = "https://ismp.crpt.ru/api/v3";
    private static final String DEMO_BASE_URL = "https://markirovka.demo.crpt.tech/api/v3";
    private static final String AUTH_CERT_KEY_ENDPOINT = "/auth/cert/key";
    private static final String AUTH_CERT_ENDPOINT = "/auth/cert/";
    private static final String CREATE_DOCUMENT_ENDPOINT = "/lk/documents/create";

    private static final String CONTENT_TYPE = "application/json";

    private final HttpClient httpClient;
    private final Gson gson;
    private final Semaphore requestSemaphore;
    private final int requestLimit;
    private final Lock resetLock;
    private final ScheduledExecutorService scheduler;
    private final String baseUrl;
    private String authToken;

    public enum Environment {
        PRODUCTION, DEMO
    }

    /**
     * Creates a new CrptApi instance with the specified rate limiting.
     *
     * @param timeUnit     The time unit for the rate limit interval
     * @param requestLimit The maximum number of requests allowed in the specified time unit
     * @param environment  The environment to use (PRODUCTION or DEMO)
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit, Environment environment) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("Request limit must be positive");
        }

        this.httpClient = HttpClient.newHttpClient();
        this.gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new LocalDateAdapter())
                .setPrettyPrinting()
                .create();
        this.requestLimit = requestLimit;
        this.requestSemaphore = new Semaphore(requestLimit);
        this.resetLock = new ReentrantLock();
        this.scheduler = Executors.newScheduledThreadPool(1);
        this.baseUrl = environment == Environment.PRODUCTION ? PRODUCTION_BASE_URL : DEMO_BASE_URL;

        // Schedule periodic permit replenishment
        long periodInMillis = timeUnit.toMillis(1);
        scheduler.scheduleAtFixedRate(this::resetPermits, periodInMillis, periodInMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Creates a new CrptApi instance with the specified rate limiting, using the production environment.
     *
     * @param timeUnit     The time unit for the rate limit interval
     * @param requestLimit The maximum number of requests allowed in the specified time unit
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        this(timeUnit, requestLimit, Environment.PRODUCTION);
    }

    /**
     * Reset available permits according to the rate limit.
     */
    private void resetPermits() {
        resetLock.lock();
        try {
            requestSemaphore.drainPermits();
            requestSemaphore.release(requestLimit);
        } finally {
            resetLock.unlock();
        }
    }

    /**
     * Authenticate with the API using certificate.
     *
     * @param certificateSigner Function to sign certificate data with УКЭП
     * @return The authorization token
     * @throws IOException          If there's an error during the HTTP request
     * @throws InterruptedException If the thread is interrupted
     * @throws ApiException         If the API returns an error
     */
    public String authenticate(CertificateSigner certificateSigner)
            throws IOException, InterruptedException, ApiException {
        // First, get the authentication key
        AuthKeyResponse keyResponse = executeRequest(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + AUTH_CERT_KEY_ENDPOINT))
                        .GET()
                        .build(),
                AuthKeyResponse.class
        );

        // Sign the received data with the provided signer
        String signedData = certificateSigner.sign(keyResponse.getData());

        // Create the authentication request
        AuthRequest authRequest = new AuthRequest(keyResponse.getUuid(), signedData);
        String authRequestJson = gson.toJson(authRequest);

        // Send the signed data to get a token
        HttpRequest authHttpRequest = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + AUTH_CERT_ENDPOINT))
                .header("Content-Type", CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(authRequestJson))
                .build();

        AuthResponse authResponse = executeRequest(authHttpRequest, AuthResponse.class);

        // Store and return the token
        this.authToken = authResponse.getToken();
        return this.authToken;
    }

    /**
     * Interface for signing certificate data with УКЭП.
     */
    @FunctionalInterface
    public interface CertificateSigner {
        /**
         * Sign the provided data with УКЭП.
         *
         * @param data The data to sign
         * @return The signed data in base64 format
         */
        String sign(String data);
    }

    /**
     * Authentication request.
     */
    @RequiredArgsConstructor
    private static class AuthRequest {
        @SerializedName("uuid")
        private final String uuid;

        @SerializedName("data")
        private final String data;
    }

    /**
     * Authentication response.
     */
    @Getter
    private static class AuthResponse {
        @SerializedName("token")
        private String token;

        @SerializedName("code")
        private String code;

        @SerializedName("error_message")
        private String errorMessage;

        @SerializedName("description")
        private String description;
    }

    /**
     * Creates a document in the Честный знак system.
     *
     * @param documentFormat  The format of the document (MANUAL, XML, CSV)
     * @param productDocument The document content encoded in Base64
     * @param signature       The detached signature (УКЭП) in Base64
     * @param type            The document type (e.g., LP_INTRODUCE_GOODS)
     * @param productGroup    The product group code (e.g., "milk", "shoes", etc.)
     * @return The API response
     * @throws InterruptedException If the thread is interrupted while waiting for a permit
     * @throws IOException          If there's an I/O error during the HTTP request
     * @throws ApiException         If the API returns an error
     * @throws TimeoutException     If the request times out due to rate limiting
     */
    public CreateDocumentResponse createDocument(
            DocumentFormat documentFormat,
            String productDocument,
            String signature,
            DocumentType type,
            ProductGroup productGroup)
            throws InterruptedException, IOException, ApiException, TimeoutException {

        // Check if we have an auth token
        if (authToken == null || authToken.isEmpty()) {
            throw new IllegalStateException("Authentication token is missing. Call authenticate() first.");
        }

        // Acquire a permit, blocking if necessary
        if (!requestSemaphore.tryAcquire()) {
            throw new TimeoutException("Request rate limit exceeded. Try again later.");
        }

        try {
            // Create the request body
            UnifiedDocumentRequest requestBody = new UnifiedDocumentRequest(
                    documentFormat.getValue(),
                    productDocument,
                    productGroup.getCode(),
                    signature,
                    type.getValue()
            );

            String requestBodyJson = gson.toJson(requestBody);

            // Build and send the HTTP request with product group query parameter
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + CREATE_DOCUMENT_ENDPOINT + "?pg=" + productGroup.getCode()))
                    .header("Content-Type", CONTENT_TYPE)
                    .header("Authorization", "Bearer " + authToken)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson))
                    .build();

            return executeRequest(request, CreateDocumentResponse.class);

        } catch (IOException | ApiException e) {
            throw new IOException("Error creating document: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            // Always release the permit
            requestSemaphore.release();
        }
    }

    /**
     * Creates a document for introducing Russian-produced goods into circulation.
     * This is a convenience method that uses the unified document creation endpoint.
     *
     * @param document     The document content
     * @param signature    The digital signature for the document
     * @param productGroup The product group
     * @return The API response
     * @throws InterruptedException If the thread is interrupted while waiting for a permit
     * @throws IOException          If there's an I/O error during the HTTP request
     * @throws ApiException         If the API returns an error
     * @throws TimeoutException     If the request times out due to rate limiting
     */
    public CreateDocumentResponse createIntroduceGoodsDocument(Document document, String signature, ProductGroup productGroup)
            throws InterruptedException, IOException, ApiException, TimeoutException {
        // Convert the document to Base64-encoded JSON string
        String documentJson = gson.toJson(document);
        String base64Document = java.util.Base64.getEncoder().encodeToString(documentJson.getBytes());

        return createDocument(
                DocumentFormat.MANUAL,
                base64Document,
                signature,
                DocumentType.LP_INTRODUCE_GOODS,
                productGroup
        );
    }

    /**
     * Document format enum.
     */
    @Getter
    @RequiredArgsConstructor
    public enum DocumentFormat {
        MANUAL("MANUAL"),
        XML("XML"),
        CSV("CSV");

        private final String value;
    }

    /**
     * Product group enum.
     */
    @Getter
    @RequiredArgsConstructor
    public enum ProductGroup {
        CLOTHES("clothes"),
        SHOES("shoes"),
        TOBACCO("tobacco"),
        PERFUMERY("perfumery"),
        TIRES("tires"),
        ELECTRONICS("electronics"),
        PHARMA("pharma"),
        MILK("milk"),
        BICYCLE("bicycle"),
        WHEELCHAIRS("wheelchairs");

        private final String code;
    }

    /**
     * Document type enum.
     */
    @Getter
    @RequiredArgsConstructor
    public enum DocumentType {
        AGGREGATION_DOCUMENT("AGGREGATION_DOCUMENT"),
        AGGREGATION_DOCUMENT_CSV("AGGREGATION_DOCUMENT_CSV"),
        AGGREGATION_DOCUMENT_XML("AGGREGATION_DOCUMENT_XML"),
        DISAGGREGATION_DOCUMENT("DISAGGREGATION_DOCUMENT"),
        DISAGGREGATION_DOCUMENT_CSV("DISAGGREGATION_DOCUMENT_CSV"),
        DISAGGREGATION_DOCUMENT_XML("DISAGGREGATION_DOCUMENT_XML"),
        REAGGREGATION_DOCUMENT("REAGGREGATION_DOCUMENT"),
        REAGGREGATION_DOCUMENT_CSV("REAGGREGATION_DOCUMENT_CSV"),
        REAGGREGATION_DOCUMENT_XML("REAGGREGATION_DOCUMENT_XML"),
        LP_INTRODUCE_GOODS("LP_INTRODUCE_GOODS"),
        LP_SHIP_GOODS("LP_SHIP_GOODS"),
        LP_SHIP_GOODS_CSV("LP_SHIP_GOODS_CSV"),
        LP_SHIP_GOODS_XML("LP_SHIP_GOODS_XML"),
        LP_INTRODUCE_GOODS_CSV("LP_INTRODUCE_GOODS_CSV"),
        LP_INTRODUCE_GOODS_XML("LP_INTRODUCE_GOODS_XML"),
        LP_ACCEPT_GOODS("LP_ACCEPT_GOODS"),
        LP_ACCEPT_GOODS_XML("LP_ACCEPT_GOODS_XML"),
        LK_REMARK("LK_REMARK"),
        LK_REMARK_CSV("LK_REMARK_CSV"),
        LK_REMARK_XML("LK_REMARK_XML"),
        LK_RECEIPT("LK_RECEIPT"),
        LK_RECEIPT_XML("LK_RECEIPT_XML"),
        LK_RECEIPT_CSV("LK_RECEIPT_CSV"),
        LP_GOODS_IMPORT("LP_GOODS_IMPORT"),
        LP_GOODS_IMPORT_CSV("LP_GOODS_IMPORT_CSV"),
        LP_GOODS_IMPORT_XML("LP_GOODS_IMPORT_XML"),
        LP_CANCEL_SHIPMENT("LP_CANCEL_SHIPMENT"),
        LP_CANCEL_SHIPMENT_CSV("LP_CANCEL_SHIPMENT_CSV"),
        LP_CANCEL_SHIPMENT_XML("LP_CANCEL_SHIPMENT_XML"),
        LK_KM_CANCELLATION("LK_KM_CANCELLATION"),
        LK_KM_CANCELLATION_CSV("LK_KM_CANCELLATION_CSV"),
        LK_KM_CANCELLATION_XML("LK_KM_CANCELLATION_XML"),
        LK_APPLIED_KM_CANCELLATION("LK_APPLIED_KM_CANCELLATION"),
        LK_APPLIED_KM_CANCELLATION_CSV("LK_APPLIED_KM_CANCELLATION_CSV"),
        LK_APPLIED_KM_CANCELLATION_XML("LK_APPLIED_KM_CANCELLATION_XML"),
        LK_CONTRACT_COMMISSIONING("LK_CONTRACT_COMMISSIONING"),
        LK_CONTRACT_COMMISSIONING_CSV("LK_CONTRACT_COMMISSIONING_CSV"),
        LK_CONTRACT_COMMISSIONING_XML("LK_CONTRACT_COMMISSIONING_XML"),
        LK_INDI_COMMISSIONING("LK_INDI_COMMISSIONING"),
        LK_INDI_COMMISSIONING_CSV("LK_INDI_COMMISSIONING_CSV"),
        LK_INDI_COMMISSIONING_XML("LK_INDI_COMMISSIONING_XML"),
        LP_SHIP_RECEIPT("LP_SHIP_RECEIPT"),
        LP_SHIP_RECEIPT_CSV("LP_SHIP_RECEIPT_CSV"),
        LP_SHIP_RECEIPT_XML("LP_SHIP_RECEIPT_XML"),
        OST_DESCRIPTION("OST_DESCRIPTION"),
        OST_DESCRIPTION_CSV("OST_DESCRIPTION_CSV"),
        OST_DESCRIPTION_XML("OST_DESCRIPTION_XML"),
        CROSSBORDER("CROSSBORDER"),
        CROSSBORDER_CSV("CROSSBORDER_CSV"),
        CROSSBORDER_XML("CROSSBORDER_XML"),
        LP_INTRODUCE_OST("LP_INTRODUCE_OST"),
        LP_INTRODUCE_OST_CSV("LP_INTRODUCE_OST_CSV"),
        LP_INTRODUCE_OST_XML("LP_INTRODUCE_OST_XML"),
        LP_RETURN("LP_RETURN"),
        LP_RETURN_CSV("LP_RETURN_CSV"),
        LP_RETURN_XML("LP_RETURN_XML"),
        LP_SHIP_GOODS_CROSSBORDER("LP_SHIP_GOODS_CROSSBORDER"),
        LP_SHIP_GOODS_CROSSBORDER_CSV("LP_SHIP_GOODS_CROSSBORDER_CSV"),
        LP_SHIP_GOODS_CROSSBORDER_XML("LP_SHIP_GOODS_CROSSBORDER_XML"),
        LP_CANCEL_SHIPMENT_CROSSBORDER("LP_CANCEL_SHIPMENT_CROSSBORDER");

        private final String value;
    }

    /**
     * Unified document request for the document creation API.
     */
    @RequiredArgsConstructor
    private static class UnifiedDocumentRequest {
        @SerializedName("document_format")
        private final String documentFormat;

        @SerializedName("product_document")
        private final String productDocument;

        @SerializedName("product_group")
        private final String productGroup;

        @SerializedName("signature")
        private final String signature;

        @SerializedName("type")
        private final String type;
    }

    /**
     * Execute an HTTP request and parse the response.
     *
     * @param request      The HTTP request to execute
     * @param responseType The class to parse the response into
     * @return The parsed response
     * @throws IOException          If there's an error during the HTTP request
     * @throws InterruptedException If the thread is interrupted
     * @throws ApiException         If the API returns an error
     */
    private <T> T executeRequest(HttpRequest request, Class<T> responseType)
            throws IOException, InterruptedException, ApiException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        // Check for error responses
        int statusCode = response.statusCode();
        if (statusCode >= 400) {
            String responseBody = response.body();
            if (responseBody != null && !responseBody.isEmpty()) {
                ApiError error = gson.fromJson(responseBody, ApiError.class);
                throw new ApiException(statusCode, error.getErrorMessage());
            } else {
                throw new ApiException(statusCode, "API returned an error with no content");
            }
        }

        // Parse the response
        return gson.fromJson(response.body(), responseType);
    }

    /**
     * Clean up resources when the API client is no longer needed.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    /**
     * Exception thrown when the API returns an error.
     */
    @Getter
    public static class ApiException extends Exception {
        private final int statusCode;

        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /**
     * API error response.
     */
    @Getter
    private static class ApiError {
        @SerializedName("error_message")
        private String errorMessage;
    }

    /**
     * Authentication key response.
     */
    @Getter
    private static class AuthKeyResponse {
        @SerializedName("uuid")
        private String uuid;

        @SerializedName("data")
        private String data;
    }

    /**
     * Response from creating a document.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateDocumentResponse {
        // Add fields based on the actual API response
        @SerializedName("document_id")
        private String documentId;

        @SerializedName("status")
        private String status;
    }

    /**
     * Document model for creating documents in the Честный знак system.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Document {
        @SerializedName("description")
        private Description description;

        @SerializedName("doc_id")
        private String docId;

        @SerializedName("doc_status")
        private String docStatus;

        @SerializedName("doc_type")
        private String docType;

        @SerializedName("importRequest")
        private boolean importRequest;

        @SerializedName("owner_inn")
        private String ownerInn;

        @SerializedName("participant_inn")
        private String participantInn;

        @SerializedName("producer_inn")
        private String producerInn;

        @SerializedName("production_date")
        private LocalDate productionDate;

        @SerializedName("production_type")
        private String productionType;

        @SerializedName("products")
        private List<Product> products;

        @SerializedName("reg_date")
        private LocalDate regDate;

        @SerializedName("reg_number")
        private String regNumber;
    }

    /**
     * Description part of the document.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Description {
        @SerializedName("participant_inn")
        private String participantInn;
    }

    /**
     * Product information.
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Product {
        @SerializedName("certificate_document")
        private String certificateDocument;

        @SerializedName("certificate_document_date")
        private LocalDate certificateDocumentDate;

        @SerializedName("certificate_document_number")
        private String certificateDocumentNumber;

        @SerializedName("owner_inn")
        private String ownerInn;

        @SerializedName("producer_inn")
        private String producerInn;

        @SerializedName("production_date")
        private LocalDate productionDate;

        @SerializedName("tnved_code")
        private String tnvedCode;

        @SerializedName("uit_code")
        private String uitCode;

        @SerializedName("uitu_code")
        private String uituCode;
    }
    
    /**
     * Gson TypeAdapter for {@link LocalDate}.
     */
    public static class LocalDateAdapter extends TypeAdapter<LocalDate> {
        private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

        @Override
        public void write(JsonWriter out, LocalDate value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(FORMATTER.format(value));
            }
        }

        @Override
        public LocalDate read(JsonReader in) throws IOException {
            if (in.peek() == com.google.gson.stream.JsonToken.NULL) {
                in.nextNull();
                return null;
            } else {
                return LocalDate.parse(in.nextString(), FORMATTER);
            }
        }
    }
} 