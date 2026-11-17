package edu.sisinf.chuspadb.servicio;

import com.fasterxml.jackson.core.JacksonException;
import edu.sisinf.chuspadb.core.ErrorPersistencia;
import edu.sisinf.chuspadb.dao.IConexionDAO;
import edu.sisinf.chuspadb.modelo.Conexion;
import edu.sisinf.chuspadb.modelo.ImportacionResultado;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportadorJSONValidacion1439Test {
    @TempDir
    Path temp;
    private final Conexion datos = new Conexion();
    private final IConexionDAO dao = mock(IConexionDAO.class);
    private final ImportadorJSON importador = new ImportadorJSON();

    @Test
    void rechazaRutaNulaAntesDeAbrirJdbc() {
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class,
                () -> importador.importar(null, "usuarios", datos, dao));
        assertTrue(error.getMessage().contains("ruta"));
        verifyNoInteractions(dao);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "usuarios; DROP TABLE usuarios"})
    void rechazaTablaInvalidaAntesDeLeerElArchivo(String tabla) {
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class,
                () -> importador.importar(temp.resolve("ausente.json"), tabla, datos, dao));
        assertTrue(error.getMessage().contains("Identificador de tabla inválido"));
        verifyNoInteractions(dao);
    }

    @Test
    void rechazaDatosDeConexionNulos() {
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class,
                () -> importador.importar(temp.resolve("ausente.json"), "usuarios", null, dao));
        assertTrue(error.getMessage().contains("datos de conexión"));
        verifyNoInteractions(dao);
    }

    @Test
    void rechazaDaoNulo() {
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class,
                () -> importador.importar(temp.resolve("ausente.json"), "usuarios", datos, null));
        assertTrue(error.getMessage().contains("DAO"));
    }

    @Test
    void conservaCausaSiNoSeEncuentraElArchivo() {
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class,
                () -> importar(temp.resolve("ausente.json")));
        assertInstanceOf(NoSuchFileException.class, error.getCause());
        verifyNoInteractions(dao);
    }

    @Test
    void rechazaContenidoDespuesDelArrayAntesDeConectar() throws IOException {
        Path archivo = escribir("[{\"id\":1}] {\"id\":2}");
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class, () -> importar(archivo));
        assertInstanceOf(JacksonException.class, error.getCause());
        verifyNoInteractions(dao);
    }

    @Test
    void rechazaIdentificadorDeColumnaAntesDeConectar() throws IOException {
        Path archivo = escribir("[{\"columna inválida\":1}]");
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class, () -> importar(archivo));
        assertTrue(error.getMessage().contains("Identificador de columna inválido"));
        verifyNoInteractions(dao);
    }

    @Test
    void informaSiElDaoDevuelveConexionNula() throws IOException, SQLException {
        Path archivo = escribir("[{\"id\":1}]");
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class, () -> importar(archivo));
        assertTrue(error.getMessage().contains("JDBC abierta"));
        verify(dao).abrir(datos);
    }

    @Test
    void informaSiElDaoDevuelveConexionCerrada() throws Exception {
        Connection conexion = DriverManager.getConnection("jdbc:sqlite::memory:");
        conexion.close();
        when(dao.abrir(datos)).thenReturn(conexion);

        Path archivo = escribir("[{\"id\":1}]");
        ErrorPersistencia error = assertThrows(ErrorPersistencia.class, () -> importar(archivo));
        assertTrue(error.getMessage().contains("JDBC abierta"));
    }

    @Test
    void conservaLaCausaDeUnFalloDelDao() throws Exception {
        Path archivo = escribir("[{\"id\":1}]");
        SQLException causa = new SQLException("fallo al abrir conexión", "08001");
        when(dao.abrir(datos)).thenThrow(causa);

        ErrorPersistencia error = assertThrows(ErrorPersistencia.class, () -> importar(archivo));

        assertSame(causa, error.getCause());
        assertTrue(error.getMessage().contains("base de datos"));
    }

    @Test
    void insertaLasFilasValidasAunqueUnaFalleEnSqlite() throws Exception {
        String url = "jdbc:sqlite:" + temp.resolve("importacion.db");
        try (var conexion = DriverManager.getConnection(url); var statement = conexion.createStatement()) {
            statement.executeUpdate("CREATE TABLE usuarios (id INTEGER PRIMARY KEY, nombre TEXT)");
        }
        Connection conexionImportacion = DriverManager.getConnection(url);
        when(dao.abrir(datos)).thenReturn(conexionImportacion);
        Path archivo = escribir("""
                [{"id":1,"nombre":"Ana"},{"id":1,"nombre":"Duplicado"},{"id":2,"nombre":null}]
                """);

        var resultado = importar(archivo);

        assertEquals(2, resultado.filasInsertadas());
        assertEquals(1, resultado.filasFallidas());
        assertTrue(resultado.errores().get(0).startsWith("Fila 2:"));
        assertTrue(conexionImportacion.isClosed());
        try (var conexion = DriverManager.getConnection(url);
             var statement = conexion.createStatement();
             var filas = statement.executeQuery("SELECT id, nombre FROM usuarios ORDER BY id")) {
            assertTrue(filas.next());
            assertEquals("Ana", filas.getString("nombre"));
            assertTrue(filas.next());
            assertEquals(2, filas.getInt("id"));
            assertNull(filas.getString("nombre"));
            assertFalse(filas.next());
        }
    }

    private Path escribir(String json) throws IOException {
        return Files.writeString(temp.resolve("datos.json"), json);
    }

    private ImportacionResultado importar(Path archivo) {
        return importador.importar(archivo, "usuarios", datos, dao);
    }
}
