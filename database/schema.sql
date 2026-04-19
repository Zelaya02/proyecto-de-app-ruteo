CREATE TABLE IF NOT EXISTS clientes (
    id SERIAL PRIMARY KEY,
    nombre VARCHAR(255) NOT NULL,
    direccion TEXT,
    latitud DOUBLE PRECISION,
    longitud DOUBLE PRECISION,
    telefono VARCHAR(50),
    activo BOOLEAN DEFAULT true
);
