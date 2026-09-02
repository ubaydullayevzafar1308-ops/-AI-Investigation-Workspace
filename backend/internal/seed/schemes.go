package seed

import (
	"time"

	"github.com/tiredjon/cbu/backend/internal/domain"
)

func (b *builder) clientAcc() string  { return domain.OwnerClient }
func (b *builder) companyAcc() string { return domain.OwnerCompany }

// Structuring — триггерит R01. 4 перевода чуть ниже порога за 2 дня.
func (b *builder) structuringScheme() (int64, error) {
	clientID, err := b.insertClient("Rashidov Sardor Anvarovich", "device-struct-01", false)
	if err != nil {
		return 0, err
	}
	clientAcc, err := b.insertAccount(b.clientAcc(), clientID)
	if err != nil {
		return 0, err
	}
	cpCompany, err := b.insertCompany("OOO \"Counterparty Trade\"", time.Now().UTC().AddDate(-2, 0, 0), nil, false)
	if err != nil {
		return 0, err
	}
	cpAcc, err := b.insertAccount(b.companyAcc(), cpCompany)
	if err != nil {
		return 0, err
	}

	base := time.Now().UTC().AddDate(0, 0, -10)
	amounts := []int64{96_000_000, 97_500_000, 95_200_000, 98_800_000}
	var lastTx int64
	for i, amt := range amounts {
		lastTx, err = b.insertTx(clientAcc, cpAcc, amt, domain.TxTypeTransfer,
			base.Add(time.Duration(i)*14*time.Hour), "Оплата по договору поставки")
		if err != nil {
			return 0, err
		}
	}
	return b.insertAlert(lastTx, clientID, "R01_STRUCTURING_PATTERN", "high")
}

// Circular flow — триггерит R03. K -> A -> B -> K.
func (b *builder) circularScheme() (int64, error) {
	clientID, err := b.insertClient("Yusupov Bekzod Islomovich", "device-circ-01", false)
	if err != nil {
		return 0, err
	}
	clientAcc, _ := b.insertAccount(b.clientAcc(), clientID)

	compA, err := b.insertCompany("OOO \"Zarafshon Capital\"", time.Now().UTC().AddDate(0, -4, 0), ptr(clientID), false)
	if err != nil {
		return 0, err
	}
	compAAcc, _ := b.insertAccount(b.companyAcc(), compA)
	compB, err := b.insertCompany("OOO \"Registon Holding\"", time.Now().UTC().AddDate(0, -3, 0), nil, false)
	if err != nil {
		return 0, err
	}
	compBAcc, _ := b.insertAccount(b.companyAcc(), compB)

	base := time.Now().UTC().AddDate(0, 0, -6)
	if _, err := b.insertTx(clientAcc, compAAcc, 300_000_000, domain.TxTypeTransfer, base, "Инвестиция в проект"); err != nil {
		return 0, err
	}
	if _, err := b.insertTx(compAAcc, compBAcc, 290_000_000, domain.TxTypeTransfer, base.Add(20*time.Hour), "Оплата субподряда"); err != nil {
		return 0, err
	}
	closing, err := b.insertTx(compBAcc, clientAcc, 285_000_000, domain.TxTypeTransfer, base.Add(44*time.Hour), "Возврат займа")
	if err != nil {
		return 0, err
	}

	_ = b.insertRel(b.clientAcc(), clientID, "company", compA, domain.RelationDirector)
	_ = b.insertRel("company", compA, "company", compB, domain.RelationFrequentCounterparty)

	return b.insertAlert(closing, clientID, "R03_CIRCULAR_FLOW", "high")
}

// Rapid movement / transit — триггерит R02.
func (b *builder) transitScheme() (int64, error) {
	clientID, err := b.insertClient("Nazarov Otabek Rustamovich", "device-transit-01", false)
	if err != nil {
		return 0, err
	}
	clientAcc, _ := b.insertAccount(b.clientAcc(), clientID)

	src, err := b.insertCompany("OOO \"Aral Trans\"", time.Now().UTC().AddDate(-1, 0, 0), nil, false)
	if err != nil {
		return 0, err
	}
	srcAcc, _ := b.insertAccount(b.companyAcc(), src)
	dst, err := b.insertCompany("OOO \"Xorazm Trans\"", time.Now().UTC().AddDate(0, -8, 0), nil, false)
	if err != nil {
		return 0, err
	}
	dstAcc, _ := b.insertAccount(b.companyAcc(), dst)

	in := time.Now().UTC().AddDate(0, 0, -3)
	if _, err := b.insertTx(srcAcc, clientAcc, 180_000_000, domain.TxTypeTransfer, in, "Оплата консультационных услуг"); err != nil {
		return 0, err
	}
	out, err := b.insertTx(clientAcc, dstAcc, 176_000_000, domain.TxTypeTransfer, in.Add(3*time.Hour), "Возврат по договору")
	if err != nil {
		return 0, err
	}
	return b.insertAlert(out, clientID, "R02_RAPID_MOVEMENT", "medium")
}

// New entity spike — триггерит R04.
func (b *builder) newCompanySpikeScheme() (int64, error) {
	clientID, err := b.insertClient("Ergashev Farrux Jasurovich", "device-spike-01", false)
	if err != nil {
		return 0, err
	}
	clientAcc, _ := b.insertAccount(b.clientAcc(), clientID)

	newCompany, err := b.insertCompany("OOO \"Fargona Agro\"", time.Now().UTC().AddDate(0, 0, -30), ptr(clientID), false)
	if err != nil {
		return 0, err
	}
	newAcc, _ := b.insertAccount(b.companyAcc(), newCompany)

	base := time.Now().UTC().AddDate(0, 0, -5)
	if _, err := b.insertTx(clientAcc, newAcc, 120_000_000, domain.TxTypeTransfer, base, "Оплата поставки оборудования"); err != nil {
		return 0, err
	}
	last, err := b.insertTx(clientAcc, newAcc, 110_000_000, domain.TxTypeTransfer, base.AddDate(0, 0, 1), "Оплата поставки, часть 2")
	if err != nil {
		return 0, err
	}
	_ = b.insertRel(b.clientAcc(), clientID, "company", newCompany, domain.RelationDirector)
	return b.insertAlert(last, clientID, "R04_NEW_ENTITY_SPIKE", "medium")
}

// Fan-in / Fan-out — триггерит R09.
func (b *builder) fanInOutScheme() (int64, error) {
	clientID, err := b.insertClient("Turdiev Islom Davronovich", "device-fanio-01", false)
	if err != nil {
		return 0, err
	}
	clientAcc, _ := b.insertAccount(b.clientAcc(), clientID)

	collector, err := b.insertCompany("OOO \"Yashnobod Market\"", time.Now().UTC().AddDate(-1, 0, 0), nil, false)
	if err != nil {
		return 0, err
	}
	collAcc, _ := b.insertAccount(b.companyAcc(), collector)

	base := time.Now().UTC().AddDate(0, 0, -3)
	inbound := []int64{18_000_000, 22_000_000, 19_500_000, 21_000_000, 20_500_000}
	for i, amt := range inbound {
		if _, err := b.insertTx(collAcc, clientAcc, amt, domain.TxTypeTransfer,
			base.Add(time.Duration(i)*8*time.Hour), "Возврат долга"); err != nil {
			return 0, err
		}
	}
	out, err := b.insertTx(clientAcc, collAcc, 101_000_000, domain.TxTypeTransfer, base.Add(48*time.Hour), "Единый перевод по итогу расчётов")
	if err != nil {
		return 0, err
	}
	return b.insertAlert(out, clientID, "R09_FAN_IN_OUT", "medium")
}

// Golden Case — главный демо-кейс: R01 + R03 + R04 + R07 + repeat offender.
func (b *builder) goldenCaseScheme() (int64, error) {
	sharedDevice := "device-golden-shared-001"

	blacklisted, err := b.insertClient("Xolmatov Jasur Anvarovich", sharedDevice, true)
	if err != nil {
		return 0, err
	}
	clientID, err := b.insertClient("Karimov Alisher Botirovich", sharedDevice, false)
	if err != nil {
		return 0, err
	}
	clientAcc, _ := b.insertAccount(b.clientAcc(), clientID)

	comp1, err := b.insertCompany("OOO \"Barakat Trade\"", time.Now().UTC().AddDate(0, -5, 0), ptr(clientID), false)
	if err != nil {
		return 0, err
	}
	comp1Acc, _ := b.insertAccount(b.companyAcc(), comp1)
	comp2, err := b.insertCompany("OOO \"Vega Import\"", time.Now().UTC().AddDate(0, -4, 0), nil, false)
	if err != nil {
		return 0, err
	}
	comp2Acc, _ := b.insertAccount(b.companyAcc(), comp2)
	comp3, err := b.insertCompany("OOO \"Ipak Yoli Trading\"", time.Now().UTC().AddDate(0, 0, -45), ptr(clientID), false)
	if err != nil {
		return 0, err
	}
	comp3Acc, _ := b.insertAccount(b.companyAcc(), comp3)

	base := time.Now().UTC().AddDate(0, 0, -14)
	structuring := []int64{96_500_000, 98_200_000, 95_800_000, 97_100_000}
	for i, amt := range structuring {
		if _, err := b.insertTx(clientAcc, comp3Acc, amt, domain.TxTypeTransfer,
			base.Add(time.Duration(i)*14*time.Hour), "Оплата поставки, транш"); err != nil {
			return 0, err
		}
	}

	cycle := base.AddDate(0, 0, 5)
	if _, err := b.insertTx(clientAcc, comp1Acc, 400_000_000, domain.TxTypeTransfer, cycle, "Инвестиция в развитие"); err != nil {
		return 0, err
	}
	if _, err := b.insertTx(comp1Acc, comp2Acc, 390_000_000, domain.TxTypeTransfer, cycle.Add(18*time.Hour), "Оплата услуг"); err != nil {
		return 0, err
	}
	closing, err := b.insertTx(comp2Acc, clientAcc, 385_000_000, domain.TxTypeTransfer, cycle.Add(40*time.Hour), "Возврат займа учредителю")
	if err != nil {
		return 0, err
	}

	_ = b.insertRel(b.clientAcc(), clientID, "company", comp1, domain.RelationDirector)
	_ = b.insertRel("company", comp1, "company", comp2, domain.RelationFrequentCounterparty)
	_ = b.insertRel(b.clientAcc(), clientID, "company", comp3, domain.RelationDirector)
	_ = b.insertRel(b.clientAcc(), clientID, b.clientAcc(), blacklisted, domain.RelationSameDevice)

	pastTx, err := b.insertTx(clientAcc, comp1Acc, 50_000_000, domain.TxTypeTransfer, base.AddDate(0, -4, 0), "Прошлая операция")
	if err != nil {
		return 0, err
	}
	if _, err := b.insertAlert(pastTx, clientID, "PAST_SUSPICIOUS_TRANSFER", "medium"); err != nil {
		return 0, err
	}

	return b.insertAlert(closing, clientID, "GOLDEN_CASE_DEMO", "high")
}
