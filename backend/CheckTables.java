import java.sql.*;
public class CheckTables {
    public static void main(String[] args) throws Exception {
        Connection conn = DriverManager.getConnection("jdbc:postgresql://localhost:5000/ruteo_db", "postgres", "Zelaya1103");
        ResultSet rs = conn.getMetaData().getTables(null, null, "%", new String[] {"TABLE"});
        while(rs.next()) {
            System.out.println(rs.getString("TABLE_NAME"));
        }
        conn.close();
    }
}
