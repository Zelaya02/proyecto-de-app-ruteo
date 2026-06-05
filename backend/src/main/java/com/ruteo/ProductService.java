package com.ruteo;

public class ProductService {
    public Product findById(Long id) {
        // En una aplicación real esto consultaría la base de datos.
        // Para la prueba devolvemos un objeto estático.
        return new Product(id, "Laptop");
    }
}
