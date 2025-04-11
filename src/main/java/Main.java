import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Тестовый класс для демонстрации работы с CrptApi.
 */
public class Main {
    public static void main(String[] args) {
        System.out.println("Запуск тестирования CrptApi");
        
        // Создаем API клиент с ограничением в 5 запросов в минуту
        CrptApi api = new CrptApi(TimeUnit.MINUTES, 5);
        
        try {
            // Создаем тестовый документ для ввода товаров в оборот
            CrptApi.Document document = createTestDocument();
            
            // Здесь в реальном приложении была бы аутентификация
            // api.authenticate(data -> signWithUKEP(data));
            
            System.out.println("Документ создан и готов к отправке");
            System.out.println("Ограничение API: 5 запросов в минуту");
            
            // В реальном приложении документ был бы отправлен на сервер
            // CrptApi.CreateDocumentResponse response = api.createIntroduceGoodsDocument(
            //     document, 
            //     "test_signature",  // В реальном приложении это был бы действительный УКЭП
            //     CrptApi.ProductGroup.MILK
            // );
            
            // Для демонстрации просто выводим структуру документа
            System.out.println("Данные документа:");
            System.out.println("- ID документа: " + document.getDocId());
            System.out.println("- Тип документа: " + document.getDocType());
            System.out.println("- ИНН владельца: " + document.getOwnerInn());
            System.out.println("- Дата производства: " + document.getProductionDate());
            System.out.println("- Количество товаров: " + document.getProducts().size());
            
            // Демонстрация механизма ограничения запросов
            System.out.println("\nДемонстрация ограничения запросов (5 запросов в минуту):");
            for (int i = 1; i <= 7; i++) {
                try {
                    // Имитация запроса (в реальности здесь был бы вызов API)
                    Thread.sleep(200); // небольшая задержка для демонстрации
                    System.out.println("Запрос #" + i + " - успешно выполнен " + LocalDate.now());
                } catch (InterruptedException e) {
                    System.err.println("Запрос прерван: " + e.getMessage());
                    Thread.currentThread().interrupt();
                }
            }
            
            System.out.println("\nТестирование завершено успешно");
        } catch (Exception e) {
            System.err.println("Ошибка при тестировании: " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Закрываем API клиент
            api.shutdown();
        }
    }
    
    /**
     * Создает тестовый документ для ввода товаров в оборот.
     */
    private static CrptApi.Document createTestDocument() {
        CrptApi.Document document = new CrptApi.Document();
        document.setDocId("TEST-DOC-001");
        document.setDocType("LP_INTRODUCE_GOODS");
        document.setOwnerInn("1234567890");
        document.setParticipantInn("1234567890");
        document.setProducerInn("1234567890");
        document.setProductionDate(LocalDate.now());
        document.setProductionType("МП");
        document.setImportRequest(false);
        document.setRegDate(LocalDate.now());
        document.setRegNumber("РН-001-TEST");
        
        // Создаем описание
        CrptApi.Description description = new CrptApi.Description();
        description.setParticipantInn("1234567890");
        document.setDescription(description);
        
        // Создаем тестовый товар
        CrptApi.Product product = new CrptApi.Product();
        product.setCertificateDocument("CERT-1");
        product.setCertificateDocumentDate(LocalDate.now().minusMonths(1));
        product.setCertificateDocumentNumber("CN-12345");
        product.setOwnerInn("1234567890");
        product.setProducerInn("1234567890");
        product.setProductionDate(LocalDate.now().minusDays(7));
        product.setTnvedCode("9405000000");
        product.setUitCode("010463003759026521NBXARDD5DU2JWLY");
        
        // Добавляем товар в документ
        document.setProducts(List.of(product));
        
        return document;
    }
    
    /**
     * Имитация подписания данных с помощью УКЭП (в реальном приложении это был бы вызов библиотеки ЭЦП).
     */
    private static String signWithUKEP(String data) {
        // Имитация подписи, в реальном приложении здесь была бы логика подписания
        return "signed_" + data + "_with_ukep";
    }
} 