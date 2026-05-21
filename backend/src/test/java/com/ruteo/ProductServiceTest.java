package com.ruteo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pruebas unitarias para ProductService.
 */
@SpringBootTest
class ProductServiceTest {

    @Autowired
    private ProductService service;

    @Test
    @DisplayName("findById devuelve el producto con el nombre correcto")
    void testFindById() {
        // Arrange – nada que preparar
        // Act
        Product result = service.findById(1L);
        // Assert
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Laptop");
    }
}
