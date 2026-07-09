import { BrowserRouter, Routes, Route, Link } from 'react-router-dom';
import AlertsList from './pages/AlertsList';
import CaseDetail from './pages/CaseDetail';

export default function App() {
  return (
    <BrowserRouter>
      <div className="min-h-screen bg-slate-950 text-slate-100">
        <nav className="border-b border-slate-800 px-6 py-3 flex items-center gap-6">
          <Link to="/" className="font-semibold text-slate-200 hover:text-white">
            AI Case Intelligence
          </Link>
          <span className="text-xs text-slate-600">AML-расследования, собранные автоматически</span>
        </nav>

        <Routes>
          <Route path="/" element={<AlertsList />} />
          <Route path="/cases/:caseId" element={<CaseDetail />} />
        </Routes>
      </div>
    </BrowserRouter>
  );
}

