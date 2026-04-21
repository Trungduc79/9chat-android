package vn.chat9.app.data.model

data class LoginRequest(val username: String, val password: String)
data class RegisterRequest(val name: String, val phone: String, val password: String)

data class AuthData(
    val user: User,
    val access_token: String,
    val refresh_token: String
)
