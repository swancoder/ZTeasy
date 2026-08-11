import { useState } from 'react'
import { useAuth } from 'react-oidc-context'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import AppBar from '@mui/material/AppBar'
import Toolbar from '@mui/material/Toolbar'
import Tabs from '@mui/material/Tabs'
import Tab from '@mui/material/Tab'
import PolicyDashboard from './PolicyDashboard'
import AuditTrail from './AuditTrail'
import Identities from './Identities'
import Inventory from './Inventory'

type View = 'policies' | 'audit' | 'identities' | 'inventory'

export default function App() {
  const auth = useAuth()
  const [view, setView] = useState<View>('policies')

  if (auth.isLoading) {
    return (
      <Box sx={{ display: 'flex', justifyContent: 'center', mt: 8 }}>
        <CircularProgress />
      </Box>
    )
  }

  if (auth.error) {
    return (
      <Box sx={{ p: 4 }}>
        <Typography color="error">Authentication error: {auth.error.message}</Typography>
        <Button variant="contained" sx={{ mt: 2 }} onClick={() => auth.signinRedirect()}>
          Retry sign in
        </Button>
      </Box>
    )
  }

  if (!auth.isAuthenticated) {
    return (
      <Box
        sx={{
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          height: '100vh',
          gap: 2,
        }}
      >
        <Typography variant="h4">ZTE Admin Console</Typography>
        <Typography color="text.secondary">Sign in with your Keycloak account to continue.</Typography>
        <Button variant="contained" size="large" onClick={() => auth.signinRedirect()}>
          Sign in
        </Button>
      </Box>
    )
  }

  const accessToken = auth.user?.access_token ?? ''

  return (
    <Box>
      <AppBar position="static">
        <Toolbar sx={{ gap: 2 }}>
          <Typography variant="h6" sx={{ flexGrow: 1 }}>
            ZTE Admin Console
          </Typography>
          <Typography variant="body2">{auth.user?.profile.preferred_username}</Typography>
          <Button color="inherit" onClick={() => auth.removeUser()}>
            Sign out
          </Button>
        </Toolbar>
        <Tabs
          value={view}
          onChange={(_, next: View) => setView(next)}
          textColor="inherit"
          indicatorColor="secondary"
        >
          <Tab value="policies" label="Policies" />
          <Tab value="audit" label="Audit Trail" />
          <Tab value="identities" label="Identities" />
          <Tab value="inventory" label="Registry" />
        </Tabs>
      </AppBar>
      {view === 'policies' && <PolicyDashboard accessToken={accessToken} />}
      {view === 'audit' && <AuditTrail accessToken={accessToken} />}
      {view === 'identities' && <Identities accessToken={accessToken} />}
      {view === 'inventory' && <Inventory accessToken={accessToken} />}
    </Box>
  )
}
