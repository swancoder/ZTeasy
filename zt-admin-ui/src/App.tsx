import { useAuth } from 'react-oidc-context'
import Box from '@mui/material/Box'
import Button from '@mui/material/Button'
import CircularProgress from '@mui/material/CircularProgress'
import Typography from '@mui/material/Typography'
import AppBar from '@mui/material/AppBar'
import Toolbar from '@mui/material/Toolbar'
import PolicyDashboard from './PolicyDashboard'

export default function App() {
  const auth = useAuth()

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
      </AppBar>
      <PolicyDashboard accessToken={auth.user?.access_token ?? ''} />
    </Box>
  )
}
