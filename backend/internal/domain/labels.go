package domain

// RelationLabelRU — человекочитаемое название типа связи для explanation-
// текстов и Evidence-заголовков.
func RelationLabelRU(relationType string) string {
	switch relationType {
	case "same_address":
		return "общий адрес"
	case "same_phone":
		return "общий телефон"
	case "same_device":
		return "общее устройство"
	case "director":
		return "общий директор"
	case "founder":
		return "общий учредитель"
	case "family":
		return "родственная связь"
	case "frequent_counterparty":
		return "частый контрагент"
	default:
		return relationType
	}
}
