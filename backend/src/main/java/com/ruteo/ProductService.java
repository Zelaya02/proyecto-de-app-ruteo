package com.ruteo;

import org.springframework.stereotype.Service;

@Service
public class ProductService {
    public Product findById(Long id) {
        // En una aplicación real esto consultaría la base de datos.
        // Para la prueba devolvemos un objeto estático.
        return new Product(id, "Laptop");
    }
}
