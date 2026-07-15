package com.cardioai.iomt.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cardioai.iomt.core.AppContainer
import com.cardioai.iomt.data.storage.SecureKey
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var backendUrl by remember { mutableStateOf(container.apiClient.baseUrl.ifBlank { "https://cardioai-api-lmy5.onrender.com" }) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showSignup by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("CARDIO AI", fontSize = 28.sp, fontWeight = FontWeight.Black)
            Text("HEART MATTERS", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(28.dp))

            if (!showSignup) {
                Text("Sign in to CardioAI", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = backendUrl, onValueChange = { backendUrl = it },
                    label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Email") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it },
                    label = { Text("Password") }, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                )

                errorMessage?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        container.apiClient.baseUrl = backendUrl
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val result = container.authService.login(email, password)
                            isLoading = false
                            result.onFailure { errorMessage = it.message ?: "Sign in failed" }
                        }
                    },
                    enabled = !isLoading && email.isNotBlank() && password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isLoading) "Signing in..." else "Sign In")
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        container.apiClient.baseUrl = backendUrl
                        isLoading = true
                        errorMessage = null
                        scope.launch {
                            val result = container.authService.signInWithGoogle(context)
                            isLoading = false
                            result.onFailure { errorMessage = it.message ?: "Google sign-in failed" }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign in with Google")
                }
                Text(
                    "Patients: use Google Sign-In. Clinical staff: use email/password.",
                    fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp),
                )

                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { showSignup = true }) {
                    Text("No account yet? Create one")
                }
            } else {
                SignupForm(
                    container = container,
                    backendUrl = backendUrl,
                    onBackendUrlChange = { backendUrl = it },
                    onBack = { showSignup = false },
                )
            }
        }
    }
}

@Composable
private fun SignupForm(
    container: AppContainer,
    backendUrl: String,
    onBackendUrlChange: (String) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("nurse") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    Text("Request a clinical staff account", fontSize = 20.sp, fontWeight = FontWeight.Bold)
    Text(
        "New accounts require administrator approval before you can sign in.",
        fontSize = 12.sp, modifier = Modifier.padding(vertical = 6.dp),
    )

    OutlinedTextField(value = backendUrl, onValueChange = onBackendUrlChange, label = { Text("Backend URL") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = organization, onValueChange = { organization = it }, label = { Text("Organization / Hospital") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Hospital Email") }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password (min 8 characters)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))

    Row {
        listOf("nurse", "cardiologist", "admin").forEach { r ->
            FilterChip(
                selected = role == r, onClick = { role = r },
                label = { Text(r.replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }

    errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    successMessage?.let { Text(it, color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }

    Spacer(Modifier.height(16.dp))
    Button(
        onClick = {
            container.apiClient.baseUrl = backendUrl
            isLoading = true
            errorMessage = null
            scope.launch {
                val result = container.authService.signup(email, name, organization, password, role)
                isLoading = false
                result.onSuccess { successMessage = it.ifBlank { "Account created — waiting for administrator approval." } }
                result.onFailure { errorMessage = it.message ?: "Signup failed" }
            }
        },
        enabled = !isLoading,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(if (isLoading) "Submitting..." else "Request Account")
    }

    Spacer(Modifier.height(12.dp))
    TextButton(onClick = onBack) { Text("Already have an account? Sign in") }
}
