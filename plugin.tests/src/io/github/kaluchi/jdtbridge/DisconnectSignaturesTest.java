package io.github.kaluchi.jdtbridge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Locale-by-locale match cases for {@link DisconnectSignatures}. */
public class DisconnectSignaturesTest {

    @Test
    void posixBrokenPipe() {
        assertTrue(DisconnectSignatures.matches(
                "java.io.IOException: Broken pipe"));
    }

    @Test
    void posixConnectionReset() {
        assertTrue(DisconnectSignatures.matches(
                "Connection reset by peer"));
    }

    @Test
    void windowsEnglishConnectionAborted() {
        assertTrue(DisconnectSignatures.matches(
                "An established connection was aborted"
                + " by the software in your host machine"));
    }

    @Test
    void windowsEnglishForciblyClosed() {
        assertTrue(DisconnectSignatures.matches(
                "An existing connection was forcibly closed"
                + " by the remote host"));
    }

    @Test
    void windowsRussianAborted() {
        // Locale-specific form actually emitted by Windows ru-RU
        // (programma na vashem khost-kompyutere razorvala
        // ustanovlennoe podklyuchenie).
        assertTrue(DisconnectSignatures.matches(
                "Программа на вашем хост-компьютере"
                + " разорвала установленное подключение"));
    }

    @Test
    void windowsRussianTerminatedByHost() {
        assertTrue(DisconnectSignatures.matches(
                "Удалённое подключение было разорвано"));
    }

    @Test
    void windowsRussianTerminated() {
        assertTrue(DisconnectSignatures.matches(
                "Подключение прервано"));
    }

    @Test
    void windowsGermanAborted() {
        assertTrue(DisconnectSignatures.matches(
                "Eine vorhandene Verbindung wurde vom"
                + " Remotehost abgebrochen"));
    }

    @Test
    void windowsGermanExisting() {
        assertTrue(DisconnectSignatures.matches(
                "Eine bestehende Verbindung wurde getrennt"));
    }

    @Test
    void windowsFrenchInterrupted() {
        assertTrue(DisconnectSignatures.matches(
                "Une connexion existante a dû être"
                + " interrompue par l'hôte distant"));
    }

    @Test
    void windowsFrenchAbandoned() {
        assertTrue(DisconnectSignatures.matches(
                "Une connexion a été abandonnée par"
                + " votre logiciel hôte"));
    }

    @Test
    void windowsSpanish() {
        assertTrue(DisconnectSignatures.matches(
                "Una conexión existente fue cerrada"
                + " forzosamente por el host remoto"));
    }

    @Test
    void unrelatedMessageDoesNotMatch() {
        assertFalse(DisconnectSignatures.matches(
                "java.lang.NullPointerException: foo"));
        assertFalse(DisconnectSignatures.matches(
                "Permission denied"));
        assertFalse(DisconnectSignatures.matches(""));
    }

    @Test
    void partialPhraseDoesNotMatch() {
        // Each multi-word locale signature requires both parts
        // to be present — partial keyword match must NOT trip.
        assertFalse(DisconnectSignatures.matches(
                "Verbindung wurde aufgebaut"),
                "German 'Verbindung wurde' alone (no abgebrochen)"
                + " is not a disconnect");
        assertFalse(DisconnectSignatures.matches(
                "connexion existante a été établie"),
                "French 'connexion existante a' alone (no"
                + " interrompue) is not a disconnect");
    }
}
