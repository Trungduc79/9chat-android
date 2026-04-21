package vn.chat9.app.data.api

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.*
import vn.chat9.app.data.model.*

interface ApiService {

    // Auth
    @POST("auth/login.php")
    suspend fun login(@Body body: LoginRequest): ApiResponse<AuthData>

    @POST("auth/register.php")
    suspend fun register(@Body body: RegisterRequest): ApiResponse<AuthData>

    @POST("auth/refresh-token.php")
    suspend fun refreshToken(@Body body: Map<String, String>): ApiResponse<AuthData>

    // Rooms
    @GET("rooms/list.php")
    suspend fun getRooms(): ApiResponse<List<Room>>

    @POST("rooms/delete.php")
    suspend fun deleteRoom(@Body body: Map<String, Int>): ApiResponse<Any>

    @POST("rooms/mark-unread.php")
    suspend fun markRoomUnread(@Body body: Map<String, Int>): ApiResponse<Any>

    @POST("rooms/mark-read.php")
    suspend fun markRoomRead(@Body body: Map<String, Int>): ApiResponse<Any>

    @POST("rooms/create.php")
    suspend fun createRoom(@Body body: @JvmSuppressWildcards Map<String, Any>): ApiResponse<Room>

    @GET("rooms/detail.php")
    suspend fun getRoomDetail(@Query("id") roomId: Int): ApiResponse<Room>

    // Messages
    @GET("messages/history.php")
    suspend fun getMessages(
        @Query("room_id") roomId: Int,
        @Query("limit") limit: Int = 50,
        @Query("before_id") beforeId: Int? = null
    ): ApiResponse<MessageHistory>

    @GET("messages/pinned.php")
    suspend fun getPinnedMessages(@Query("room_id") roomId: Int): ApiResponse<List<Message>>

    @POST("messages/pin-to-top.php")
    suspend fun pinToTop(@Body body: Map<String, Int>): ApiResponse<Any>

    @GET("messages/search-global.php")
    suspend fun searchMessages(@Query("q") query: String, @Query("limit") limit: Int = 10, @Query("before_id") beforeId: Int? = null): ApiResponse<List<MessageSearchResult>>

    // Transcribe voice message
    @POST("messages/transcribe.php")
    suspend fun transcribeMessage(@Body body: vn.chat9.app.data.model.TranscribeRequest): ApiResponse<vn.chat9.app.data.model.TranscribeResponse>

    // Files
    @Multipart
    @POST("files/upload.php")
    suspend fun uploadFile(
        @Part file: MultipartBody.Part,
        @Part("room_id") roomId: RequestBody
    ): ApiResponse<FileData>

    // Users
    @GET("users/search.php")
    suspend fun searchUsers(
        @Query("q") query: String,
        @Query("type") type: String
    ): ApiResponse<List<User>>

    @PUT("users/profile.php")
    suspend fun updateProfile(@Body body: Map<String, String?>): ApiResponse<User>

    @Multipart
    @POST("users/avatar.php")
    suspend fun uploadAvatar(@Part avatar: MultipartBody.Part): ApiResponse<Map<String, String>>

    // Friends
    @GET("friends/list.php")
    suspend fun getFriends(@Query("type") type: String): ApiResponse<List<Friend>>

    @POST("friends/send-request.php")
    suspend fun sendFriendRequest(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<Any>

    @POST("friends/respond.php")
    suspend fun respondFriendRequest(@Body body: Map<String, @JvmSuppressWildcards Any>): ApiResponse<Any>

    @POST("friends/unfriend.php")
    suspend fun unfriend(@Body body: Map<String, Int>): ApiResponse<Any>

    @POST("friends/alias.php")
    suspend fun setFriendAlias(@Body body: Map<String, Any?>): ApiResponse<Any>

    // Stories
    @GET("stories/list.php")
    suspend fun getStories(@Query("type") type: String): ApiResponse<List<Story>>

    @Multipart
    @POST("stories/create.php")
    suspend fun createStory(
        @Part image: MultipartBody.Part?,
        @Part("content") content: RequestBody?
    ): ApiResponse<Story>

    @Multipart
    @POST("stories/create.php")
    suspend fun createStoryMultiImage(
        @Part images: List<MultipartBody.Part>,
        @Part("content") content: RequestBody?
    ): ApiResponse<Story>

    // Reactions
    @POST("messages/reactions/add.php")
    suspend fun addReaction(@Body body: ReactionRequest): ApiResponse<Any>

    @POST("messages/reactions/remove.php")
    suspend fun removeReaction(@Body body: ReactionRemoveRequest): ApiResponse<Any>

    @GET("messages/reactions/list.php")
    suspend fun getReactions(@Query("message_id") messageId: Int): ApiResponse<vn.chat9.app.data.model.ReactionDetailResponse>

    @POST("stories/delete.php")
    suspend fun deleteStory(@Body body: Map<String, Int>): ApiResponse<Any>

    @POST("stories/view.php")
    suspend fun viewStory(@Body body: Map<String, Int>): ApiResponse<Any>

    @GET("stories/user.php")
    suspend fun getUserWall(@Query("user_id") userId: Int): ApiResponse<WallData>

    // Push
    @POST("push/subscribe.php")
    suspend fun subscribePush(@Body body: vn.chat9.app.data.model.PushSubscribeRequest): ApiResponse<Any>

    // Call
    @POST("calls/reject.php")
    suspend fun rejectCall(@Body body: Map<String, String>): ApiResponse<Any>
}
