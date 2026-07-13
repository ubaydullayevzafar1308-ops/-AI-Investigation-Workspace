package uz.caseintel.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import uz.caseintel.casebuilder.CaseBuilderService;
import uz.caseintel.casebuilder.dto.ReadyCaseDto;
import uz.caseintel.repository.AlertRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Code review item 6: два конкурентных POST /alerts/{id}/investigate для
 * одного alertId — cases.alert_id уникален на уровне БД, поэтому
 * проигравший вызов buildCase() ловит DataIntegrityViolationException при
 * вставке Case. AlertController должен поймать это и повторно вызвать
 * buildCase() (новая транзакция, findByAlertId теперь находит кейс
 * победителя), а не отдавать 500 клиенту.
 */
class AlertControllerTest {

    private final AlertRepository alertRepository = mock(AlertRepository.class);
    private final CaseBuilderService caseBuilderService = mock(CaseBuilderService.class);
    private final AlertController controller = new AlertController(alertRepository, caseBuilderService);

    @Test
    void investigate_onUniqueConstraintRace_retriesAndReturnsWinnersCase() {
        ReadyCaseDto winnerCase = new ReadyCaseDto(
                99L, 5L, 3L, "Клиент Тестов", null, null, null, "", "", "open", null, 0, 0);

        when(caseBuilderService.buildCase(5L))
                .thenThrow(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"cases_alert_id_key\""))
                .thenReturn(winnerCase);

        ReadyCaseDto result = controller.investigate(5L);

        assertThat(result).isEqualTo(winnerCase);
        verify(caseBuilderService, times(2)).buildCase(5L);
    }

    @Test
    void investigate_onNormalCall_doesNotRetry() {
        ReadyCaseDto freshCase = new ReadyCaseDto(
                1L, 2L, 3L, "Клиент Раз", null, null, null, "", "", "open", null, 0, 0);
        when(caseBuilderService.buildCase(2L)).thenReturn(freshCase);

        ReadyCaseDto result = controller.investigate(2L);

        assertThat(result).isEqualTo(freshCase);
        verify(caseBuilderService, times(1)).buildCase(2L);
    }
}
