package ru.miacomsoft.EasyWebServer.component;

import java.sql.*;

public class pgConnect {
    public Connection connect(String server, String dbName, String userName, String userPass) {
        Connection conn = null;
        Statement stmt = null;
        try {
            Class.forName("org.postgresql.Driver");
            // c = DriverManager.getConnection("jdbc:postgresql://localhost:5432/postgres","postgres", "postgres");
            conn = DriverManager.getConnection("jdbc:postgresql://" + server + "/" + dbName, userName, userPass);


            System.out.println("Opened database successfully");
            stmt = conn.createStatement();
            String sql = "CREATE TABLE  IF NOT EXISTS BUSINESS " +
                    "(ID             SERIAL PRIMARY KEY," +
                    " NAME           TEXT    NOT NULL, " +
                    " AGE            INT     NOT NULL, " +
                    " ADDRESS        CHAR(50), " +
                    " SALARY         REAL)";
            stmt.executeUpdate(sql);


            ResultSet rs = stmt.executeQuery("select * from business;");
            while (rs.next()) {
                String NAME = rs.getString("NAME");
                int AGE = rs.getInt("AGE");
                int ID = rs.getInt("ID");
                System.out.printf("ID=%s Title = %s, AGE = %s ", ID, NAME, AGE);
                System.out.println();
            }
// ---------------------------------------------------------------------------------------
            stmt.execute("" +
                    " CREATE OR REPLACE PROCEDURE myprocinout(strVar INOUT VARCHAR) language plpgsql  AS $$ \n" +
                    " BEGIN \n" +
                    "    strVar := '11111fsdfsdf';\n" +
                    " END;$$\n" );
            CallableStatement cs = conn.prepareCall("call myprocinout(?);");
            cs.registerOutParameter(1, Types.VARCHAR);
            cs.setString(1, "a string"); // Входящие переменные
            cs.execute();
            String outParam = cs.getString(1);  // Получение ответа
            System.out.println(outParam);
// ---------------------------------------------------------------------------------------

            stmt.close();
            // conn.close();
        } catch (Exception e) {
            System.err.println(e.getClass().getName() + ": " + e.getMessage());
            System.exit(0);
        }
        System.out.println("Table created successfully");
        return conn;
    }
/*


https://jdbc.postgresql.org/documentation/query/


https://www.enterprisedb.com/postgres-tutorials/how-query-postgresql-data-using-select-statement-java

      ResultSet rs = stmt.executeQuery( "select * from public.\"Album\" ;" );
      while ( rs.next() ) {
         int albumid = rs.getInt("AlbumId");
         String  title = rs.getString("Title");
         int artistid  = rs.getInt("ArtistId");
         System.out.printf( "AlbumId = %s , Title = %s, ArtistId = %s ", albumid,title, artistid );


          //Retrieving the ResultSetMetadata object
          ResultSetMetaData rsMetaData = rs.getMetaData();
          System.out.println("List of column names in the current table: ");
          //Retrieving the list of column names
          int count = rsMetaData.getColumnCount();
          for(int i = 1; i<=count; i++) {
             System.out.println(rsMetaData.getColumnName(i));
          }

         System.out.println();
      }

      rs.close();
      stmt.close();
      c.close();






https://jdbc.postgresql.org/documentation/callproc/

// Setup function to call.
Statement stmt = conn.createStatement();
stmt.execute("CREATE OR REPLACE FUNCTION refcursorfunc() RETURNS refcursor AS '" +
    " DECLARE " +
    "    mycurs refcursor; " +
    " BEGIN " +
    "    OPEN mycurs FOR SELECT 1 UNION SELECT 2; " +
    "    RETURN mycurs; " +
    " END;' language plpgsql");
stmt.close();

// We must be inside a transaction for cursors to work.
conn.setAutoCommit(false);

// Function call.
CallableStatement func = conn.prepareCall("{? = call refcursorfunc() }");
func.registerOutParameter(1, Types.OTHER);
func.execute();
ResultSet results = (ResultSet) func.getObject(1);
while (results.next()) {
    // do something with the results.
}
results.close();
func.close();



conn.setAutoCommit(false);
CallableStatement func = conn.prepareCall("{? = call refcursorfunc() }");
func.registerOutParameter(1, Types.OTHER);
func.execute();
String cursorName = func.getString(1);
func.close();




Statement stmt = conn.createStatement();
stmt.execute("CREATE OR REPLACE FUNCTION setoffunc() RETURNS SETOF int AS " +
    "' SELECT 1 UNION SELECT 2;' LANGUAGE sql");
ResultSet rs = stmt.executeQuery("SELECT * FROM setoffunc()");
while (rs.next()) {
    // do something
}
rs.close();
stmt.close();

 */

}


            /*

create or replace PROCEDURE proc1(input1 IN varchar(10), input2 IN integer, output1 INOUT integer, output2 OUT varchar(10))
LANGUAGE plpgsql
AS $$
BEGIN
output2 := input1;
output1 := input2;
END;
$$;

            String query = "INSERT INTO business(NAME, AGE) VALUES(?, ?)";
            PreparedStatement pst = conn.prepareStatement(query);
            pst.setString(1, "dasdasdasd asdasdasd asdasdas");
            pst.setInt(2, 33);
            pst.executeUpdate();

// procedure call with two OUT parameter
stmt = cn.prepareCall("call proc1(?,?,?,?)")
stmt.setString(1,'x')
stmt.setInt(2,100)
stmt.setInt(3,0)
stmt.registerOutParameter(3,Types.INTEGER)
stmt.registerOutParameter(4,Types.VARCHAR)
stmt.execute()
println  stmt.getInt(3)
println  stmt.getString(4)


http://www.java2s.com/Tutorial/Java/0340__Database/CallaprocedurewithoneINOUTparameter.htm
             */
