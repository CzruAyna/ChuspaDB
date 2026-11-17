package edu.sisinf.chuspadb.servicio;

import edu.sisinf.chuspadb.core.ErrorQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EjecutorQueryValidacion1428Test {
    private final EjecutorQuery ejecutor = new EjecutorQuery();

    @Test
    void rechazaConexionNula() {
        ErrorQuery error = assertThrows(ErrorQuery.class, () -> ejecutor.ejecutar(null, "SELECT 1"));
        assertTrue(error.getMessage().contains("conexión JDBC"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\n ", "\u2003"})
    void rechazaSqlVacioAntesDeUsarJdbc(String sql) {
        Connection conexion = mock(Connection.class);
        ErrorQuery error = assertThrows(ErrorQuery.class, () -> ejecutor.ejecutar(conexion, sql));
        assertTrue(error.getMessage().contains("sentencia SQL"));
        verifyNoInteractions(conexion);
    }

    @Test
    void rechazaConexionCerrada() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.close();
        ErrorQuery error = assertThrows(
                ErrorQuery.class, () -> ejecutor.ejecutar(connection, "SELECT 1"));
        assertTrue(error.getMessage().contains("cerrada"));
    }

    @Test
    void conservaLaCausaDeUnErrorAlCrearElStatement() throws SQLException {
        Connection conexion = mock(Connection.class);
        SQLException causa = new SQLException("fallo de JDBC", "08006");
        when(conexion.createStatement()).thenThrow(causa);

        ErrorQuery error = assertThrows(ErrorQuery.class, () -> ejecutor.ejecutar(conexion, "SELECT 1"));

        assertSame(causa, error.getCause());
        assertEquals("fallo de JDBC", error.getMessage());
        verify(conexion, never()).close();
    }

    @Test
    void propagaFalloAlVerificarLaConexionSinCrearStatement() throws SQLException {
        Connection conexion = mock(Connection.class);
        SQLException causa = new SQLException("fallo al consultar estado", "08003");
        when(conexion.isClosed()).thenThrow(causa);

        ErrorQuery error = assertThrows(ErrorQuery.class, () -> ejecutor.ejecutar(conexion, "SELECT 1"));

        assertSame(causa, error.getCause());
        verify(conexion, never()).createStatement();
    }

    @Test
    void mantieneAbiertaLaConexionDelConsumidorTrasUnaConsultaValida() throws SQLException {
        try (Connection conexion = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            var resultado = ejecutor.ejecutar(conexion, "SELECT 42 AS respuesta");
            assertEquals(42, ((Number) resultado.getFilas().get(0).get(0)).intValue());
            assertFalse(conexion.isClosed());
        }
    }
}
