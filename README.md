# Честный знак API Client

A thread-safe Java client for the Честный знак API with request rate limiting.

## Features

- Thread-safe implementation
- Rate limiting for API requests
- Support for both production and demo environments
- Support for all document types and product groups
- Proper error handling

## Usage

### Creating an API Client

```java
// Create a client allowing 10 requests per minute
CrptApi api = new CrptApi(TimeUnit.MINUTES, 10);

// Or specify the environment (PRODUCTION or DEMO)
CrptApi demoApi = new CrptApi(TimeUnit.MINUTES, 10, CrptApi.Environment.DEMO);
```

### Authentication

```java
try {
    // Create a certificate signer that signs data with your УКЭП
    CrptApi.CertificateSigner signer = data -> {
        // Implement your УКЭП signing logic here
        // This is typically done using a cryptographic library
        return signWithUKEP(data);
    };
    
    // Authenticate to get a token
    String token = api.authenticate(signer);
} catch (Exception e) {
    // Handle authentication errors
}
```

### Creating Documents

#### Using the Unified Document Creation API

```java
// Prepare your document data in Base64 format
String base64EncodedDocument = "...";
String signature = "..."; // Your УКЭП signature

try {
    // Create a document (e.g., introducing goods for milk products)
    CrptApi.CreateDocumentResponse response = api.createDocument(
        CrptApi.DocumentFormat.MANUAL,  // Format: MANUAL, XML, or CSV
        base64EncodedDocument,          // Base64 encoded document
        signature,                      // УКЭП signature
        CrptApi.DocumentType.LP_INTRODUCE_GOODS, // Document type
        CrptApi.ProductGroup.MILK       // Product group
    );
    
    System.out.println("Document created with ID: " + response.getDocumentId());
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    System.err.println("Request interrupted: " + e.getMessage());
} catch (TimeoutException e) {
    System.err.println("Rate limit exceeded: " + e.getMessage());
} catch (IOException e) {
    System.err.println("I/O error: " + e.getMessage());
} catch (CrptApi.ApiException e) {
    System.err.println("API error " + e.getStatusCode() + ": " + e.getMessage());
} finally {
    // Make sure to shut down the API client when done
    api.shutdown();
}
```

#### Using the Convenience Method for Introducing Goods

```java
// Create a document using the Document object model
CrptApi.Document document = new CrptApi.Document();
document.setDocId("doc123");
document.setDocType("LP_INTRODUCE_GOODS");
document.setOwnerInn("1234567890");
// Set other document properties

// Create a product
CrptApi.Product product = new CrptApi.Product();
product.setUitCode("uitCode123");
product.setProducerInn("1234567890");
// Set other product properties

// Add product to document
document.setProducts(List.of(product));

try {
    // Create an introduce goods document for the milk product group
    CrptApi.CreateDocumentResponse response = api.createIntroduceGoodsDocument(
        document, 
        "digital_signature",
        CrptApi.ProductGroup.MILK
    );
    
    System.out.println("Document created with ID: " + response.getDocumentId());
} catch (Exception e) {
    // Handle errors
} finally {
    api.shutdown();
}
```

## Building

This project uses Maven for dependency management. To build:

```bash
mvn clean package
```

## Requirements

- Java 11 or higher
- Maven (for building)
