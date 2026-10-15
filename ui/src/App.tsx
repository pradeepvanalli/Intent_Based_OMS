import { lazy, Suspense } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import { AppShell } from './shell/AppShell';
import { RequireAuth } from './auth/RequireAuth';
import { Loading } from './components/States';
import LoginPage from './pages/Login';

const ControlTower = lazy(() => import('./pages/controlTower/ControlTower'));
const OrdersList = lazy(() => import('./pages/orders/OrdersList'));
const OrderDetail = lazy(() => import('./pages/orders/OrderDetail'));
const Tasks = lazy(() => import('./pages/tasks/Tasks'));
const Exceptions = lazy(() => import('./pages/exceptions/Exceptions'));
const Returns = lazy(() => import('./pages/returns/Returns'));
const ReturnDetail = lazy(() => import('./pages/returns/ReturnDetail'));
const Studio = lazy(() => import('./pages/studio/Studio'));
const DecisionDetail = lazy(() => import('./pages/studio/DecisionDetail'));
const StateModels = lazy(() => import('./pages/stateModels/StateModels'));
const StateModelDetail = lazy(() => import('./pages/stateModels/StateModelDetail'));
const Agents = lazy(() => import('./pages/agents/Agents'));
const Simulation = lazy(() => import('./pages/simulation/Simulation'));
const MarginReplay = lazy(() => import('./pages/replay/MarginReplay'));
const Analytics = lazy(() => import('./pages/analytics/Analytics'));
const Settings = lazy(() => import('./pages/settings/Settings'));
const Copilot = lazy(() => import('./pages/copilot/Copilot'));
const Bootstrap = lazy(() => import('./pages/copilot/Bootstrap'));
const Showcase = lazy(() => import('./pages/showcase/Showcase'));
const Playground = lazy(() => import('./pages/playground/Playground'));
const Track = lazy(() => import('./pages/track/Track'));

export default function App() {
  return (
    <Suspense fallback={<Loading label="Loading…" />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/track/:token" element={<Track />} />
        <Route element={<RequireAuth />}>
          <Route element={<AppShell />}>
            <Route index element={<Navigate to="/control-tower" replace />} />
            <Route path="/control-tower" element={<ControlTower />} />
            <Route path="/playground" element={<Playground />} />
            <Route path="/orders" element={<OrdersList />} />
            <Route path="/orders/:id" element={<OrderDetail />} />
            <Route path="/tasks" element={<Tasks />} />
            <Route path="/exceptions" element={<Exceptions />} />
            <Route path="/returns" element={<Returns />} />
            <Route path="/returns/:rma" element={<ReturnDetail />} />
            <Route path="/studio" element={<Studio />} />
            <Route path="/studio/:name" element={<DecisionDetail />} />
            <Route path="/state-models" element={<StateModels />} />
            <Route path="/state-models/:id" element={<StateModelDetail />} />
            <Route path="/agents" element={<Agents />} />
            <Route path="/simulation" element={<Simulation />} />
            <Route path="/replay" element={<MarginReplay />} />
            <Route path="/analytics" element={<Analytics />} />
            <Route path="/copilot" element={<Copilot />} />
            <Route path="/copilot/bootstrap" element={<Bootstrap />} />
            <Route path="/settings" element={<Settings />} />
            <Route path="/showcase" element={<Showcase />} />
            <Route path="*" element={<NotFound />} />
          </Route>
        </Route>
      </Routes>
    </Suspense>
  );
}
function NotFound() {
  return <div className="page"><div className="empty"><div className="title">Page not found</div><div className="small">The route does not exist. Use the navigation on the left.</div></div></div>;
}
