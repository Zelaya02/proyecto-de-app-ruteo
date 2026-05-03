package com.ruteo.repository;

import com.ruteo.model.Usuario;
import org.mindrot.jbcrypt.BCrypt;
import java.sql.*;

public class UsuarioRepository {
    private final String dbUrl;
    private final String dbUser;
    private final String dbPass;

    public UsuarioRepository(String dbUrl, String dbUser, String dbPass) {
        this.dbUrl = dbUrl;
        this.dbUser = dbUser;
        this.dbPass = dbPass;
    }

    public Usuario login(String username, String password) {
        // Primero buscamos al usuario solo por nombre
        String sql = "SELECT * FROM usuarios WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, username);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String storedPassword = rs.getString("password");
                    boolean matches = false;

                    // Verificar si es BCrypt o texto plano
                    if (storedPassword.startsWith("$2a$")) {
                        matches = BCrypt.checkpw(password, storedPassword);
                    } else {
                        // Texto plano (legacy/migración)
                        matches = password.equals(storedPassword);
                        
                        // Si coincide en texto plano, lo migramos a BCrypt de una vez
                        if (matches) {
                            String hashed = BCrypt.hashpw(password, BCrypt.gensalt());
                            actualizarPassword(username, hashed);
                        }
                    }

                    if (matches) {
                        Usuario user = new Usuario();
                        user.setId(rs.getInt("id"));
                        user.setUsername(rs.getString("username"));
                        user.setNombre(rs.getString("nombre"));
                        user.setRol(rs.getString("rol"));
                        return user;
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("Error en login: " + e.getMessage());
        }
        return null;
    }

    private void actualizarPassword(String username, String newHash) {
        String sql = "UPDATE usuarios SET password = ? WHERE username = ?";
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newHash);
            pstmt.setString(2, username);
            pstmt.executeUpdate();
            System.out.println("✅ Password migrado a BCrypt para el usuario: " + username);
        } catch (SQLException e) {
            System.err.println("Error al migrar password: " + e.getMessage());
        }
    }
}