package uz.caseintel.datacollector;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import uz.caseintel.casebuilder.dto.DossierDto;
import uz.caseintel.casebuilder.dto.DossierDto.CompanyView;
import uz.caseintel.casebuilder.dto.DossierDto.CycleView;
import uz.caseintel.casebuilder.dto.DossierDto.PastAlertView;
import uz.caseintel.casebuilder.dto.DossierDto.RelationView;
import uz.caseintel.casebuilder.dto.DossierDto.TxView;
import uz.caseintel.entity.Account;
import uz.caseintel.entity.Alert;
import uz.caseintel.entity.Case;
import uz.caseintel.entity.Client;
import uz.caseintel.entity.Company;
import uz.caseintel.entity.Relationship;
import uz.caseintel.entity.Transaction;
import uz.caseintel.graph.CycleDetector;
import uz.caseintel.graph.GraphEngineService;
import uz.caseintel.repository.AccountRepository;
import uz.caseintel.repository.AlertRepository;
import uz.caseintel.repository.CaseRepository;
import uz.caseintel.repository.ClientRepository;
import uz.caseintel.repository.CompanyRepository;
import uz.caseintel.repository.RelationshipRepository;
import uz.caseintel.repository.TransactionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Data Collector (① в пайплайне). Единственное место в системе, где
 * сырые данные из репозиториев (клиент, счета, транзакции, связи,
 * прошлые алерты) собираются в DossierDto — единственный вход для Rule
 * Engine (②). Ни одно правило не обращается к репозиториям напрямую.
 *
 * Также запускает CycleDetector (часть Graph Engine, ③) ЗАРАНЕЕ и
 * кладёт результат в DossierDto.moneyCycles — см. развёрнутое
 * объяснение этого решения в комментарии к полю moneyCycles в
 * DossierDto.java и в javadoc R03CircularFlowRule.
 */
@Service
public class DataCollectorService {

    private final ClientRepository clientRepository;
    private final CompanyRepository companyRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final RelationshipRepository relationshipRepository;
    private final AlertRepository alertRepository;
    private final CaseRepository caseRepository;
    private final GraphEngineService graphEngineService;

    public DataCollectorService(
            ClientRepository clientRepository,
            CompanyRepository companyRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            RelationshipRepository relationshipRepository,
            AlertRepository alertRepository,
            CaseRepository caseRepository,
            GraphEngineService graphEngineService) {
        this.clientRepository = clientRepository;
        this.companyRepository = companyRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.relationshipRepository = relationshipRepository;
        this.alertRepository = alertRepository;
        this.caseRepository = caseRepository;
        this.graphEngineService = graphEngineService;
    }

    @Transactional(readOnly = true)
    public DossierDto collect(Long alertId) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));
        Client client = alert.getClient();
        if (client == null) {
            throw new IllegalStateException("Alert %d has no associated client".formatted(alertId));
        }
        return collectForClient(client.getId());
    }

    /** Публичный метод для повторной сборки досье того же клиента (например, для чата/пересчёта). */
    @Transactional(readOnly = true)
    public DossierDto collectForClient(Long clientId) {
        Client client = clientRepository.findById(clientId)
                .orElseThrow(() -> new IllegalArgumentException("Client not found: " + clientId));

        List<Account> accounts = accountRepository.findByOwnerTypeAndOwnerId(Account.OWNER_CLIENT, clientId);
        List<Long> accountIds = accounts.stream().map(Account::getId).toList();

        List<TxView> transactions = accountIds.isEmpty()
                ? List.of()
                : buildTxViews(accountIds, transactionRepository.findByAccountIdIn(accountIds));

        List<RelationView> relations = buildRelationViews(clientId);
        List<CompanyView> relatedCompanies = buildRelatedCompanies(clientId);
        List<PastAlertView> pastAlerts = buildPastAlerts(clientId);
        List<CycleView> moneyCycles = detectMoneyCycles(clientId);

        return new DossierDto(
                client.getId(),
                client.getFullName(),
                client.getInn(),
                client.getPhone(),
                client.getDeviceId(),
                client.getAddress(),
                client.getRegistrationDate(),
                client.getClientType(),
                client.isBlacklisted(),
                accountIds,
                transactions,
                relations,
                relatedCompanies,
                pastAlerts,
                moneyCycles
        );
    }

    private List<TxView> buildTxViews(List<Long> clientAccountIds, List<Transaction> rawTransactions) {
        var accountIdSet = Set.copyOf(clientAccountIds);
        List<TxView> views = new ArrayList<>();
        for (Transaction tx : rawTransactions) {
            Long fromId = tx.getFromAccount() != null ? tx.getFromAccount().getId() : null;
            Long toId = tx.getToAccount() != null ? tx.getToAccount().getId() : null;

            // direction — с точки зрения СЧЕТОВ КЛИЕНТА: если один из его счетов
            // отправитель -> "out", если получатель -> "in". Если оба конца —
            // счета самого клиента (перевод между своими счетами), считаем "out"
            // (условно, как исходящую операцию с первого счёта) — такие переводы
            // не должны влиять на большинство правил, но не должны и падать.
            String direction = accountIdSet.contains(fromId) ? TxView.DIRECTION_OUT : TxView.DIRECTION_IN;

            views.add(new TxView(
                    tx.getId(), fromId, toId, direction,
                    tx.getAmount(), tx.getCurrency(), tx.getTxType(), tx.getTxTimestamp()
            ));
        }
        return views;
    }

    /**
     * Известный компромисс производительности: toRelationView делает один
     * SELECT на каждую связь (N+1), а не batched-запрос. Для MVP это
     * приемлемо — на одного клиента обычно единицы-десятки связей, а не
     * тысячи. Если это станет узким местом на реальных объёмах, нужно
     * batch-загрузить всех counterpart'ов одним IN-запросом, как уже
     * сделано в GraphEngineService.enrichLabelsOnly.
     */
    private List<RelationView> buildRelationViews(Long clientId) {
        List<Relationship> asSource = relationshipRepository.findBySourceTypeAndSourceId(Account.OWNER_CLIENT, clientId);
        List<Relationship> asTarget = relationshipRepository.findByTargetTypeAndTargetId(Account.OWNER_CLIENT, clientId);

        List<RelationView> views = new ArrayList<>();
        for (Relationship r : asSource) {
            views.add(toRelationView(r.getTargetType(), r.getTargetId(), r.getRelationType()));
        }
        for (Relationship r : asTarget) {
            views.add(toRelationView(r.getSourceType(), r.getSourceId(), r.getRelationType()));
        }
        return views;
    }

    private RelationView toRelationView(String counterpartType, Long counterpartId, String relationType) {
        String label;
        boolean blacklisted;
        if (Account.OWNER_CLIENT.equals(counterpartType)) {
            Client c = clientRepository.findById(counterpartId).orElse(null);
            label = c != null ? c.getFullName() : "Клиент #" + counterpartId;
            blacklisted = c != null && c.isBlacklisted();
        } else {
            Company c = companyRepository.findById(counterpartId).orElse(null);
            label = c != null ? c.getName() : "Компания #" + counterpartId;
            blacklisted = c != null && c.isBlacklisted();
        }
        return new RelationView(counterpartType, counterpartId, label, relationType, blacklisted);
    }

    private List<CompanyView> buildRelatedCompanies(Long clientId) {
        List<Company> companies = companyRepository.findByDirectorId(clientId);
        List<CompanyView> views = new ArrayList<>();
        for (Company c : companies) {
            BigDecimal turnover = transactionRepository.sumTurnoverByOwner(Account.OWNER_COMPANY, c.getId());
            views.add(new CompanyView(
                    c.getId(), c.getName(), c.getRegistrationDate(), c.isBlacklisted(),
                    "director", turnover
            ));
        }
        return views;
    }

    private List<PastAlertView> buildPastAlerts(Long clientId) {
        return alertRepository.findByClientId(clientId).stream()
                .map(a -> {
                    Case relatedCase = caseRepository.findByAlertId(a.getId()).orElse(null);
                    return new PastAlertView(
                            a.getId(),
                            relatedCase != null ? relatedCase.getId() : null,
                            relatedCase != null ? relatedCase.getStatus() : null,
                            a.getCreatedAt()
                    );
                })
                .toList();
    }

    private List<CycleView> detectMoneyCycles(Long clientId) {
        GraphEngineService.MoneyFlowGraph moneyFlowGraph = graphEngineService.buildMoneyFlowGraph(clientId);
        if (moneyFlowGraph.edges().isEmpty()) {
            return List.of();
        }
        var detector = new CycleDetector(moneyFlowGraph.edges(), moneyFlowGraph.labels());
        return detector.findCyclesFrom("client_" + clientId);
    }
}
