package com.ruteo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * Pruebas unitarias para ProductService.
 */
public class ProductServiceTest {

    private ProductService service = new ProductService();

    @Test
    public void testFindById() {
        // Arrange – nada que preparar
        // Act
        Product result = service.findById(1L);
        // Assert
        assertNotNull(result);
        assertEquals(Long.valueOf(1L), result.getId());
        assertEquals("Laptop", result.getName());
    }
}
