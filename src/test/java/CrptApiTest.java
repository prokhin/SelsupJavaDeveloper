import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class CrptApiTest {

    private CrptApi api;
    private CrptApi.Document testDocument;

    @Mock
    private HttpClient mockHttpClient;

    @Mock
    private HttpResponse<String> mockHttpResponse;

    @Captor
    private ArgumentCaptor<HttpRequest> httpRequestCaptor;

    @BeforeEach
    void setUp() {
        // Initialize a test document before each test
        testDocument = createTestDocument();
    }

    @AfterEach
    void tearDown() {
        if (api != null) {
            api.shutdown();
        }
    }

    @Test
    void testRateLimiting() throws InterruptedException {
        int requestLimit = 5;
        api = new CrptApi(TimeUnit.SECONDS, requestLimit);
        
        int totalThreads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch latch = new CountDownLatch(totalThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger timeoutCount = new AtomicInteger(0);

        // Mock the http client to avoid real calls
        try {
            setMockHttpClient(api, mockHttpClient);
            when(mockHttpClient.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(mockHttpResponse);
            when(mockHttpResponse.statusCode()).thenReturn(200);
            when(mockHttpResponse.body()).thenReturn("{\"document_id\":\"test-id\",\"status\":\"OK\"}");
            
            // Set auth token to bypass authentication check
            setAuthToken(api, "dummy-token");

        } catch (Exception e) {
            fail("Failed to set up mock http client", e);
        }

        for (int i = 0; i < totalThreads; i++) {
            executor.submit(() -> {
                try {
                    api.createIntroduceGoodsDocument(testDocument, "signature", CrptApi.ProductGroup.MILK);
                    successCount.incrementAndGet();
                } catch (TimeoutException e) {
                    timeoutCount.incrementAndGet();
                } catch (Exception e) {
                    // Ignore other exceptions for this test
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertEquals(requestLimit, successCount.get(), "Should have allowed exactly " + requestLimit + " requests.");
        assertTrue(timeoutCount.get() > 0, "Should have timed out some requests.");
    }

    @Test
    void createDocument_shouldThrowIllegalStateException_whenNotAuthenticated() {
        api = new CrptApi(TimeUnit.MINUTES, 5);
        
        assertThrows(IllegalStateException.class, () -> {
            api.createIntroduceGoodsDocument(testDocument, "signature", CrptApi.ProductGroup.MILK);
        }, "Should throw IllegalStateException when auth token is missing.");
    }

    @Test
    void createIntroduceGoodsDocument_shouldBuildCorrectRequest() throws Exception {
        api = new CrptApi(TimeUnit.MINUTES, 5);
        setMockHttpClient(api, mockHttpClient);
        setAuthToken(api, "test-token");

        when(mockHttpClient.send(httpRequestCaptor.capture(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn("{\"document_id\":\"some-id\"}");

        api.createIntroduceGoodsDocument(testDocument, "test-signature", CrptApi.ProductGroup.SHOES);

        HttpRequest sentRequest = httpRequestCaptor.getValue();

        // Verify headers
        assertTrue(sentRequest.headers().firstValue("Authorization").orElse("").contains("Bearer test-token"));
        assertTrue(sentRequest.headers().firstValue("Content-Type").orElse("").contains("application/json"));

        // Verify URI
        assertTrue(sentRequest.uri().toString().endsWith("/lk/documents/create?pg=shoes"));

        String requestBody = getRequestBody(sentRequest);

        // Parse the request body to verify its contents
        Gson gson = new Gson();
        Map<String, String> bodyMap = gson.fromJson(requestBody, Map.class);

        assertEquals("LP_INTRODUCE_GOODS", bodyMap.get("type"));
        assertEquals("test-signature", bodyMap.get("signature"));
        assertEquals("shoes", bodyMap.get("product_group"));

        // Decode the product_document from Base64 and verify its content
        String decodedProductDocument = new String(Base64.getDecoder().decode(bodyMap.get("product_document")));
        Map<String, Object> productDocumentMap = gson.fromJson(decodedProductDocument, Map.class);

        assertEquals("TEST-DOC-001", productDocumentMap.get("doc_id"));
        assertEquals("1234567890", productDocumentMap.get("owner_inn"));
    }
    
    @Test
    void testLocalDateSerialization() {
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(LocalDate.class, new CrptApi.LocalDateAdapter())
                .create();
        
        LocalDate date = LocalDate.of(2023, 1, 15);
        CrptApi.Document doc = new CrptApi.Document(null, null, null, null, false, null, null, null, date, null, null, null, null);
        
        String json = gson.toJson(doc);
        
        assertTrue(json.contains("\"production_date\":\"2023-01-15\""), "JSON should contain correctly formatted LocalDate");
        
        CrptApi.Document deserializedDoc = gson.fromJson(json, CrptApi.Document.class);
        assertEquals(date, deserializedDoc.getProductionDate(), "Deserialized LocalDate should match original");
    }

    private CrptApi.Document createTestDocument() {
        CrptApi.Product product = new CrptApi.Product(
                null, null, null, null, null, null, null, "uit-code-123", null
        );

        return new CrptApi.Document(
                null, "TEST-DOC-001", null, "LP_INTRODUCE_GOODS", false,
                "1234567890", null, null, LocalDate.now(), null, Collections.singletonList(product), null, null
        );
    }

    private void setMockHttpClient(CrptApi api, HttpClient mockClient) throws NoSuchFieldException, IllegalAccessException {
        Field clientField = CrptApi.class.getDeclaredField("httpClient");
        clientField.setAccessible(true);
        clientField.set(api, mockClient);
    }

    private void setAuthToken(CrptApi api, String token) throws NoSuchFieldException, IllegalAccessException {
        Field tokenField = CrptApi.class.getDeclaredField("authToken");
        tokenField.setAccessible(true);
        tokenField.set(api, token);
    }

    private String getRequestBody(HttpRequest request) throws Exception {
        var publisher = request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody());
        var subscriber = new BodySubscriber();
        publisher.subscribe(subscriber);
        return subscriber.getBody().thenApply(bb -> StandardCharsets.UTF_8.decode(bb).toString()).get();
    }

    private static class BodySubscriber implements Flow.Subscriber<ByteBuffer> {
        private final CompletableFuture<ByteBuffer> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(ByteBuffer item) {
            body.complete(item);
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            if (!body.isDone()) {
                body.complete(ByteBuffer.wrap(new byte[0]));
            }
        }

        public CompletableFuture<ByteBuffer> getBody() {
            return body;
        }
    }
}