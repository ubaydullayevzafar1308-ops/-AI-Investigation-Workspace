import { useMemo } from 'react';

const NODE_COLORS = {
  client: '#38bdf8',
  company: '#a78bfa',
};

/**
 * Простая круговая раскладка вместо полноценного force-directed layout
 * (react-force-graph-2d из спеки) — для базового MVP этого достаточно,
 * чтобы увидеть структуру связей. Полноценную физику можно добавить
 * позже отдельным шагом, не меняя формат GraphDto с бэкенда.
 */
export default function GraphView({ graph }) {
  const layout = useMemo(() => computeCircularLayout(graph), [graph]);

  if (!graph || !graph.nodes?.length) {
    return <p className="text-sm text-slate-500">Граф пуст.</p>;
  }

  const size = 420;

  return (
    <svg viewBox={`0 0 ${size} ${size}`} className="w-full h-auto max-h-[420px]">
      {graph.edges?.map((edge, idx) => {
        const from = layout[edge.source];
        const to = layout[edge.target];
        if (!from || !to) return null;
        const isMoneyFlow = edge.kind === 'money_flow';
        return (
          <line
            key={idx}
            x1={from.x}
            y1={from.y}
            x2={to.x}
            y2={to.y}
            stroke={isMoneyFlow ? (edge.suspicious ? '#f87171' : '#475569') : '#334155'}
            strokeWidth={isMoneyFlow ? 1.5 : 1}
            strokeDasharray={isMoneyFlow ? '' : '4 3'}
          />
        );
      })}

      {graph.nodes.map((node) => {
        const pos = layout[node.id];
        if (!pos) return null;
        const color = node.flagged ? '#f87171' : NODE_COLORS[node.type] || '#94a3b8';
        return (
          <g key={node.id}>
            <circle cx={pos.x} cy={pos.y} r={node.flagged ? 10 : 8} fill={color} opacity={0.9} />
            <text
              x={pos.x}
              y={pos.y + 20}
              textAnchor="middle"
              fontSize="9"
              fill="#cbd5e1"
              className="select-none"
            >
              {truncate(node.label, 16)}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

function computeCircularLayout(graph) {
  if (!graph?.nodes?.length) return {};
  const size = 420;
  const center = size / 2;
  const radius = size / 2 - 50;

  const layout = {};
  const n = graph.nodes.length;

  if (n === 1) {
    layout[graph.nodes[0].id] = { x: center, y: center };
    return layout;
  }

  graph.nodes.forEach((node, idx) => {
    const angle = (2 * Math.PI * idx) / n - Math.PI / 2;
    layout[node.id] = {
      x: center + radius * Math.cos(angle),
      y: center + radius * Math.sin(angle),
    };
  });

  return layout;
}

function truncate(text, maxLen) {
  if (!text) return '';
  return text.length > maxLen ? text.slice(0, maxLen - 1) + '…' : text;
}
