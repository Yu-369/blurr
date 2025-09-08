package com.blurr.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import com.blurr.voice.api.ApiKeyManager
import android.graphics.Typeface
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.blurr.voice.agent.v1.AgentConfig
import com.blurr.voice.agent.v1.ClarificationAgent
import com.blurr.voice.agent.v1.InfoPool
import com.blurr.voice.agent.v1.VisionHelper
import com.blurr.voice.agent.v1.VisionMode
import com.blurr.voice.api.Eyes
//import com.blurr.voice.services.AgentTaskService
import com.blurr.voice.utilities.SpeechCoordinator
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.TextView
import androidx.annotation.RequiresApi
import androidx.core.graphics.toColorInt
import com.blurr.voice.utilities.TTSManager
import com.blurr.voice.utilities.addResponse
import com.blurr.voice.utilities.getReasoningModelApiResponse
import com.blurr.voice.data.MemoryManager
import com.blurr.voice.data.MemoryExtractor
import com.blurr.voice.utilities.FreemiumManager
import com.blurr.voice.utilities.UserProfileManager
import com.blurr.voice.utilities.VisualFeedbackManager
import com.blurr.voice.v2.AgentService
import com.blurr.voice.v2.llm.GeminiApi
import com.google.ai.client.generativeai.type.TextPart
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ModelDecision(
    val type: String = "Reply",
    val reply: String,
    val instruction: String = "",
    val shouldEnd: Boolean = false
)

class ConversationalAgentService : Service() {

    private val speechCoordinator by lazy { SpeechCoordinator.getInstance(this) }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var conversationHistory = listOf<Pair<String, List<Any>>>()
    private val ttsManager by lazy { TTSManager.getInstance(this) }
    private val clarificationQuestionViews = mutableListOf<View>()
    private var transcriptionView: TextView? = null
    private val visualFeedbackManager by lazy { VisualFeedbackManager.getInstance(this) }
    private var isTextModeActive = false
    private val freemiumManager by lazy { FreemiumManager() }

    // Add these at the top of your ConversationalAgentService class
    private var clarificationAttempts = 0
    private val maxClarificationAttempts = 1
    private var sttErrorAttempts = 0
    private val maxSttErrorAttempts = 2

     private val clarificationAgent = ClarificationAgent()
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val memoryManager by lazy { MemoryManager.getInstance(this) }
    private val usedMemories = mutableSetOf<String>() // Track memories already used in this conversation
    private lateinit var firebaseAnalytics: FirebaseAnalytics
    private val eyes by lazy { Eyes(this) }


    companion object {
        const val NOTIFICATION_ID = 3
        const val CHANNEL_ID = "ConversationalAgentChannel"
        var isRunning = false
    }

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreate() {
        super.onCreate()
        Log.d("ConvAgent", "Service onCreate")
        
        // Initialize Firebase Analytics
        firebaseAnalytics = Firebase.analytics
        
        // Track service creation
        firebaseAnalytics.logEvent("conversational_agent_started", null)
        
        isRunning = true
        createNotificationChannel()
        initializeConversation()
        ttsManager.setCaptionsEnabled(true)
        clarificationAttempts = 0 // Reset clarification attempts counter
        sttErrorAttempts = 0 // Reset STT error attempts counter
        usedMemories.clear() // Clear used memories for new conversation
        visualFeedbackManager.showTtsWave()
        showInputBoxIfNeeded()
        visualFeedbackManager.showSpeakingOverlay() // <-- ADD THIS LINE


    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun showInputBoxIfNeeded() {
        // This function ensures the input box is always configured correctly
        // whether it's the first time or a subsequent turn in text mode.
        visualFeedbackManager.showInputBox(
            onActivated = {
                // This is called when the user taps the EditText
                enterTextMode()
            },
            onSubmit = { submittedText ->
                // This is the existing callback for when text is submitted
                processUserInput(submittedText)
            },
            onOutsideTap = {
                serviceScope.launch {
                    instantShutdown()
                }
            }
        )
    }

    /**
     * Call this when the user starts interacting with the text input.
     * It stops any ongoing voice interaction.
     */
    private fun enterTextMode() {
        if (isTextModeActive) return
        Log.d("ConvAgent", "Entering Text Mode. Stopping STT/TTS.")
        
        // Track text mode activation
        firebaseAnalytics.logEvent("text_mode_activated", null)
        
        isTextModeActive = true
        speechCoordinator.stopListening()
        speechCoordinator.stopSpeaking()
        // Optionally hide the transcription view since user is typing
        visualFeedbackManager.hideTranscription()
    }


    @RequiresApi(Build.VERSION_CODES.R)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ConvAgent", "Service onStartCommand")
        startForeground(NOTIFICATION_ID, createNotification())

        // Track conversation initiation
        firebaseAnalytics.logEvent("conversation_initiated", null)

        serviceScope.launch {
            if (conversationHistory.size == 1) {
                val greeting = getPersonalizedGreeting()
                conversationHistory = addResponse("model", greeting, conversationHistory)
                speakAndThenListen(greeting)
            }
        }
        return START_STICKY
    }

    /**
     * Gets a personalized greeting using the user's name from memories if available
     */
    private fun getPersonalizedGreeting(): String {
        try {
            val userProfile = UserProfileManager(this@ConversationalAgentService)
            Log.d("ConvAgent", "No name found in memories, using generic greeting")
            return "Hey ${userProfile.getName()}!"
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error getting personalized greeting", e)
            return "Hey!"
        }
    }


    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun speakAndThenListen(text: String, draw: Boolean = true) {
        updateSystemPromptWithMemories()
        ttsManager.setCaptionsEnabled(draw)

        speechCoordinator.speakText(text)
        Log.d("ConvAgent", "Panda said: $text")
        // --- CHANGE 4: Check if we are in text mode before starting to listen ---
        if (isTextModeActive) {
            Log.d("ConvAgent", "In text mode, ensuring input box is visible and skipping voice listening.")
            // Post to main handler to ensure UI operations are on the main thread.
            mainHandler.post {
                showInputBoxIfNeeded() // Re-show the input box for the next turn.
            }
            return // IMPORTANT: Skip starting the voice listener entirely.
        }
        speechCoordinator.startListening(
            onResult = { recognizedText ->
                if (isTextModeActive) return@startListening // Ignore errors in text mode
                Log.d("ConvAgent", "Final user transcription: $recognizedText")
                visualFeedbackManager.updateTranscription(recognizedText)
                mainHandler.postDelayed({
                    visualFeedbackManager.hideTranscription()
                }, 500)
                processUserInput(recognizedText)

            },
            onError = { error ->
                Log.e("ConvAgent", "STT Error: $error")
                if (isTextModeActive) return@startListening // Ignore errors in text mode
                
                // Track STT errors
                val sttErrorBundle = android.os.Bundle().apply {
                    putString("error_message", error.take(100))
                    putInt("error_attempt", sttErrorAttempts + 1)
                    putInt("max_attempts", maxSttErrorAttempts)
                }
                firebaseAnalytics.logEvent("stt_error", sttErrorBundle)
                
                visualFeedbackManager.hideTranscription()
                sttErrorAttempts++
                serviceScope.launch {
                    if (sttErrorAttempts >= maxSttErrorAttempts) {
                        firebaseAnalytics.logEvent("conversation_ended_stt_errors", null)
                        val exitMessage = "I'm having trouble understanding you clearly. Please try calling later!"
                        gracefulShutdown(exitMessage)
                    } else {
                        speakAndThenListen("I'm sorry, I didn't catch that. Could you please repeat?")
                    }
                }
            },
            onPartialResult = { partialText ->
                if (isTextModeActive) return@startListening // Ignore errors in text mode
                visualFeedbackManager.updateTranscription(partialText)
            },
            onListeningStateChange = { listening ->
                Log.d("ConvAgent", "Listening state: $listening")
                if (listening) {
                    if (isTextModeActive) return@startListening // Ignore errors in text mode
                    visualFeedbackManager.showTranscription()
                }
            }
        )
        ttsManager.setCaptionsEnabled(true)
    }

    // START: ADD THESE NEW METHODS AT THE END OF THE CLASS, before onDestroy()
    private fun showTranscriptionView() {
        if (transcriptionView != null) return // Already showing

        mainHandler.post {
            transcriptionView = TextView(this).apply {
                text = "Listening..."
                val glassBackground = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xDD0D0D2E.toInt(), 0xDD2A0D45.toInt())
                ).apply {
                    cornerRadius = 28f
                    setStroke(1, 0x80FFFFFF.toInt())
                }
                background = glassBackground
                setTextColor(0xFFE0E0E0.toInt())
                textSize = 16f
                setPadding(40, 24, 40, 24)
                typeface = Typeface.MONOSPACE
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 250 // Position it 250px above the bottom edge
            }

            try {
                windowManager.addView(transcriptionView, params)
            } catch (e: Exception) {
                Log.e("ConvAgent", "Failed to add transcription view.", e)
                transcriptionView = null
            }
        }
    }

    private fun updateTranscriptionView(text: String) {
        transcriptionView?.text = text
    }

    private fun hideTranscriptionView() {
        mainHandler.post {
            transcriptionView?.let {
                if (it.isAttachedToWindow) {
                    try {
                        windowManager.removeView(it)
                    } catch (e: Exception) {
                        Log.e("ConvAgent", "Error removing transcription view.", e)
                    }
                }
            }
            transcriptionView = null
        }
    }


    // --- CHANGED: Rewritten to process the new custom text format ---
    @RequiresApi(Build.VERSION_CODES.R)
    private fun processUserInput(userInput: String) {
        serviceScope.launch {
            removeClarificationQuestions()
            updateSystemPromptWithAgentStatus()

            conversationHistory = addResponse("user", userInput, conversationHistory)

            // Track user input
            val inputBundle = android.os.Bundle().apply {
                putString("input_type", if (isTextModeActive) "text" else "voice")
                putInt("input_length", userInput.length)
                putBoolean("is_command", userInput.equals("stop", ignoreCase = true) || userInput.equals("exit", ignoreCase = true))
            }
            firebaseAnalytics.logEvent("user_input_processed", inputBundle)

            try {
                if (userInput.equals("stop", ignoreCase = true) || userInput.equals("exit", ignoreCase = true)) {
                    firebaseAnalytics.logEvent("conversation_ended_by_command", null)
                    gracefulShutdown("Goodbye!")
                    return@launch
                }

                val rawModelResponse = getReasoningModelApiResponse(conversationHistory) ?: "### Type ###\nReply\n### Reply ###\nI'm sorry, I had an issue.\n### Instruction ###\n\n### Should End ###\nContinue"
                val decision = parseModelResponse(rawModelResponse)

                when (decision.type) {
                    "Task" -> {
                        // Track task request
                        val taskBundle = android.os.Bundle().apply {
                            putString("task_instruction", decision.instruction.take(100)) // Limit length for analytics
                            putBoolean("agent_already_running", AgentService.isRunning)
                        }
                        firebaseAnalytics.logEvent("task_requested", taskBundle)
                        
                        if (AgentService.isRunning) {
                            firebaseAnalytics.logEvent("task_rejected_agent_busy", null)
                            val busyMessage = "I'm already working on '${AgentService.currentTask}'. Please let me finish that first, or you can ask me to stop it."
                            speakAndThenListen(busyMessage)
                            return@launch
                        }
                        Log.d("ConvAgent", "Model identified a task. Checking for clarification...")
                        // --- NEW: Check if the task instruction needs clarification ---
                        removeClarificationQuestions()
                        if(freemiumManager.canPerformTask()){
                            Log.d("ConvAgent", "Allowance check passed. Proceeding with task.")

                            freemiumManager.decrementTaskCount()
                            if (clarificationAttempts < maxClarificationAttempts) {
                                val (needsClarification, questions) = checkIfClarificationNeeded(
                                    decision.instruction
                                )
                                Log.d("ConcAgent", needsClarification.toString())
                                Log.d("ConcAgent", questions.toString())

                                if (needsClarification) {
                                    // Track clarification needed
                                    val clarificationBundle = android.os.Bundle().apply {
                                        putInt("clarification_attempt", clarificationAttempts + 1)
                                        putInt("questions_count", questions.size)
                                    }
                                    firebaseAnalytics.logEvent("task_clarification_needed", clarificationBundle)
                                    
                                    clarificationAttempts++
                                    displayClarificationQuestions(questions)
                                    val questionToAsk =
                                        "I can help with that, but first: ${questions.joinToString(" and ")}"
                                    Log.d(
                                        "ConvAgent",
                                        "Task needs clarification. Asking: '$questionToAsk' (Attempt $clarificationAttempts/$maxClarificationAttempts)"
                                    )
                                    conversationHistory = addResponse(
                                        "model",
                                        "Clarification needed for task: ${decision.instruction}",
                                        conversationHistory
                                    )
                                    speakAndThenListen(questionToAsk, false)
                                } else {
                                    Log.d(
                                        "ConvAgent",
                                        "Task is clear. Executing: ${decision.instruction}"
                                    )
                                    
                                    // Track task execution
                                    firebaseAnalytics.logEvent("task_executed", taskBundle)
                                    
                                    val originalInstruction = decision.instruction
                                    AgentService.start(applicationContext, originalInstruction)
                                    gracefulShutdown(decision.reply)
                                }
                            } else {
                                Log.d(
                                    "ConvAgent",
                                    "Max clarification attempts reached ($maxClarificationAttempts). Proceeding with task execution."
                                )
                                
                                // Track max clarification attempts reached
                                firebaseAnalytics.logEvent("task_executed_max_clarification", taskBundle)
                                
                                AgentService.start(applicationContext, decision.instruction)
                                gracefulShutdown(decision.reply)
                            }
                        }else{
                            Log.w("ConvAgent", "User has no tasks remaining. Denying request.")
                            
                            // Track freemium limit reached
                            firebaseAnalytics.logEvent("task_rejected_freemium_limit", null)
                            
                            val upgradeMessage = "${getPersonalizedGreeting()} You've used all your free tasks for the month. Please upgrade in the app to unlock more. We can still talk in voice mode."
                            conversationHistory = addResponse("model", upgradeMessage, conversationHistory)
                            speakAndThenListen(upgradeMessage)
                        }
                    }
                    "KillTask" -> {
                        Log.d("ConvAgent", "Model requested to kill the running agent service.")
                        
                        // Track kill task request
                        val killTaskBundle = android.os.Bundle().apply {
                            putBoolean("task_was_running", AgentService.isRunning)
                        }
                        firebaseAnalytics.logEvent("kill_task_requested", killTaskBundle)
                        
                        if (AgentService.isRunning) {
                            AgentService.stop(applicationContext)
                            gracefulShutdown(decision.reply)
                        } else {
                            speakAndThenListen("There was no automation running, but I can help with something else.")
                        }
                    }
                    else -> { // Default to "Reply"
                        // Track conversational reply
                        val replyBundle = android.os.Bundle().apply {
                            putBoolean("conversation_ended", decision.shouldEnd)
                            putInt("reply_length", decision.reply.length)
                        }
                        firebaseAnalytics.logEvent("conversational_reply", replyBundle)
                        
                        if (decision.shouldEnd) {
                            Log.d("ConvAgent", "Model decided to end the conversation.")
                            firebaseAnalytics.logEvent("conversation_ended_by_model", null)
                            gracefulShutdown(decision.reply)
                        } else {
                            conversationHistory = addResponse("model", rawModelResponse, conversationHistory)
                            speakAndThenListen(decision.reply)
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("ConvAgent", "Error processing user input: ${e.message}", e)
                
                // Track processing errors
                val errorBundle = android.os.Bundle().apply {
                    putString("error_message", e.message?.take(100) ?: "Unknown error")
                    putString("error_type", e.javaClass.simpleName)
                }
                firebaseAnalytics.logEvent("input_processing_error", errorBundle)
                
                speakAndThenListen("closing voice mode")
            }
        }
    }
    private suspend fun getGroundedStepsForTask(taskInstruction: String): String {
        Log.d("ConvAgent", "Performing grounded search for task: '$taskInstruction'")

        // We create a specific prompt for the search.
        val searchPrompt = """
        Search the web and provide a concise, step-by-step guide for a human assistant to perform the following task on an Android phone: '$taskInstruction'.
        Focus on the exact taps and settings involved.
    """.trimIndent()

        // Here we use the direct REST API call with search that we created previously.
        // We need an instance of GeminiApi to call it.
        // NOTE: You might need to adjust how you get your GeminiApi instance.
        // For now, we'll assume we can create one or access it.
        val geminiApi = GeminiApi("gemini-2.5-flash", ApiKeyManager, 2)

        val searchResult = geminiApi.generateGroundedContent(searchPrompt)
        Log.d("CONVO_SEARCH", searchResult.toString())
        return if (!searchResult.isNullOrBlank()) {
            searchResult
        } else {
            ""
        }
    }
    // --- NEW: Added the clarification check logic directly into the service ---
    private suspend fun checkIfClarificationNeeded(instruction: String): Pair<Boolean, List<String>> {
        try {
            val tempInfoPool = InfoPool(instruction = instruction)
            // Use 'this' as the context for the service
            val config = AgentConfig(visionMode = VisionMode.XML, apiKey = "", context = this)

            Log.d("ConvAgent", "Checking clarification with conversation history (${conversationHistory.size} messages)")
            val prompt = clarificationAgent.getPromptWithHistory(tempInfoPool, config, conversationHistory)
            val chat = clarificationAgent.initChat()
            val combined = VisionHelper.createChatResponse("user", prompt, chat, config)
            val response = withContext(Dispatchers.IO) {
                getReasoningModelApiResponse(combined)
            }

            val parsedResult = clarificationAgent.parseResponse(response.toString())
            val status = parsedResult["status"] ?: "CLEAR"
            val questionsText = parsedResult["questions"] ?: ""

            Log.d("ConvAgent", "Clarification check result: status=$status, questions=${questionsText.take(100)}...")

            return if (status == "NEEDS_CLARIFICATION" && questionsText.isNotEmpty()) {
                val questions = clarificationAgent.parseQuestions(questionsText)
                Log.d("ConvAgent", "Clarification needed. Questions: $questions")
                Pair(true, questions)
            } else {
                Log.d("ConvAgent", "No clarification needed or no questions generated")
                Pair(false, emptyList())
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error checking for clarification", e)
            return Pair(false, emptyList())
        }
    }


    private fun initializeConversation() {
        val systemPrompt = """
            You are a helpful voice assistant called Panda that can either have a conversation or ask executor to execute tasks on the user's phone.
            The executor can speak, listen, see screen, tap screen, and basically use the phone as normal human would

            {agent_status_context}

            ### Current Screen Context ###
            {screen_context}
            ### End Screen Context ###

            Some Guideline:
            1. If the user ask you to do something creative, you do this task and be the most creative person in the world.
            2. If you know the user's name from the memories, refer to them by their name to make the conversation more personal and friendly.
            3. Use the current screen context to better understand what the user is looking at and provide more relevant responses.
            4. If the user asks about something on the screen, you can reference the screen content directly.

            Use these memories to answer the user's question with his personal data
            ### Memory Context Start ###
            {memory_context}
            ### Memory Context Ends ###
        
            Analyze the user's request and respond in the following format:

            ### Type ###
            Either "Task", "Reply", or "KillTask".
            - Use "Task" if the user is asking you to DO something on the device (e.g., "open settings", "send a text to Mom", "post a tweet").
            - Use "Reply" for conversational questions (e.g., "what's the weather?", "tell me a joke", "how are you?").
            - Use "KillTask" ONLY if an automation task is running and the user wants to stop it.

            ### Reply ###
            The conversational text to speak to the user.
            - If it's a task, this should be a confirmation, like "Okay, opening settings." or "Sure, I can do that.".
            - If it's a reply, this is the answer to the user's question.
            - If you know the user's name, use it naturally in your responses to make the conversation more personal.

            ### Instruction ###
            - If Type is "Task", provide the precise, literal instruction for the task agent here. This should be a complete command.
            - If Type is "Reply" or "KillTask", this field should be empty.

            ### Should End ###
            "Continue" or "Finished". Use "Finished" only when the conversation is naturally over.
        """.trimIndent()

        conversationHistory = addResponse("user", systemPrompt, emptyList())
    }

    private fun updateSystemPromptWithAgentStatus() {
        val currentPromptText = conversationHistory.firstOrNull()?.second
            ?.filterIsInstance<TextPart>()?.firstOrNull()?.text ?: return

        val agentStatusContext = if (AgentService.isRunning) {
            """
            IMPORTANT CONTEXT: An automation task is currently running in the background.
            Task Description: "${AgentService.currentTask}".
            If the user asks to stop, cancel, or kill this task, you MUST use the "KillTask" type.
            """.trimIndent()
        } else {
            "CONTEXT: No automation task is currently running."
        }

        val updatedPromptText = currentPromptText.replace("{agent_status_context}", agentStatusContext)

        // Replace the first system message with the updated prompt
        conversationHistory = conversationHistory.toMutableList().apply {
            set(0, "user" to listOf(TextPart(updatedPromptText)))
        }
        Log.d("ConvAgent", "System prompt updated with agent status: ${AgentService.isRunning}")
    }

    /**
     * Gets current screen context using the Eyes class
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun getScreenContext(): String {
        return try {
            val currentApp = eyes.getCurrentActivityName()
            val screenXml = eyes.openXMLEyes()
            val keyboardStatus = eyes.getKeyBoardStatus()
            
            // Track screen context usage
            val screenContextBundle = android.os.Bundle().apply {
                putString("current_app", currentApp.take(50)) // Limit length for analytics
                putBoolean("keyboard_visible", keyboardStatus)
                putInt("screen_xml_length", screenXml.length)
            }
            firebaseAnalytics.logEvent("screen_context_captured", screenContextBundle)
            
            """
            Current App: $currentApp
            Keyboard Visible: $keyboardStatus
            Screen Content:
            $screenXml
            """.trimIndent()
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error getting screen context", e)
            
            // Track screen context errors
            val errorBundle = android.os.Bundle().apply {
                putString("error_message", e.message?.take(100) ?: "Unknown error")
                putString("error_type", e.javaClass.simpleName)
            }
            firebaseAnalytics.logEvent("screen_context_error", errorBundle)
            
            "Screen context unavailable"
        }
    }

    /**
     * Updates the system prompt with relevant memories and current screen context
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun updateSystemPromptWithMemories() {
        try {
            // Get current screen context
            val screenContext = getScreenContext()
            Log.d("ConvAgent", "Retrieved screen context: ${screenContext.take(200)}...")
            
            // Get the last user message to search for relevant memories
            val lastUserMessage = conversationHistory.lastOrNull { it.first == "user" }
                ?.second?.filterIsInstance<TextPart>()
                ?.joinToString(" ") { it.text } ?: ""

            // Get current prompt
            val currentPrompt = conversationHistory.first().second
                .filterIsInstance<TextPart>()
                .firstOrNull()?.text ?: ""

            // Update screen context first
            var updatedPrompt = currentPrompt.replace("{screen_context}", screenContext)

            if (lastUserMessage.isNotEmpty()) {
                Log.d("ConvAgent", "Searching for memories relevant to: ${lastUserMessage.take(100)}...")

                var relevantMemories = memoryManager.searchMemories(lastUserMessage, topK = 5).toMutableList() // Get more memories to filter from
                val nameMemories = memoryManager.searchMemories("name", topK = 2)
                relevantMemories.addAll(nameMemories)
                if (relevantMemories.isNotEmpty()) {
                    Log.d("ConvAgent", "Found ${relevantMemories.size} relevant memories")

                    // Filter out memories that have already been used in this conversation
                    val newMemories = relevantMemories.filter { memory ->
                        !usedMemories.contains(memory)
                    }.take(20) // Limit to top 20 new memories

                    if (newMemories.isNotEmpty()) {
                        Log.d("ConvAgent", "Adding ${newMemories.size} new memories to context")

                        // Add new memories to the used set
                        newMemories.forEach { usedMemories.add(it) }

                        val currentMemoryContext = extractCurrentMemoryContext(updatedPrompt)
                        val allMemories = (currentMemoryContext + newMemories).distinct()

                        // Update the system prompt with all memories
                        val memoryContext = allMemories.joinToString("\n") { "- $it" }
                        updatedPrompt = updatedPrompt.replace("{memory_context}", memoryContext)

                        Log.d("ConvAgent", "Updated system prompt with ${allMemories.size} total memories (${newMemories.size} new)")
                    } else {
                        Log.d("ConvAgent", "No new memories to add (all relevant memories already used)")
                        // Still need to replace the placeholder if no new memories
                        val currentMemoryContext = extractCurrentMemoryContext(updatedPrompt)
                        val memoryContext = currentMemoryContext.joinToString("\n") { "- $it" }
                        updatedPrompt = updatedPrompt.replace("{memory_context}", memoryContext)
                    }
                } else {
                    Log.d("ConvAgent", "No relevant memories found")
                    // Replace with empty context if no memories found
                    updatedPrompt = updatedPrompt.replace("{memory_context}", "No relevant memories found")
                }
            } else {
                // Replace with empty context if no user message
                updatedPrompt = updatedPrompt.replace("{memory_context}", "")
            }

            if (updatedPrompt.isNotEmpty()) {
                // Replace the first system message with updated prompt
                conversationHistory = conversationHistory.toMutableList().apply {
                    set(0, "user" to listOf(TextPart(updatedPrompt)))
                }
                Log.d("ConvAgent", "Updated system prompt with screen context and memories")
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error updating system prompt with memories and screen context", e)
        }
    }

    /**
     * Extracts current memory context from the system prompt
     */
    private fun extractCurrentMemoryContext(prompt: String): List<String> {
        return try {
            val memorySection = prompt.substringAfter("##### MEMORY CONTEXT #####")
                .substringBefore("##### END MEMORY CONTEXT #####")
                .trim()

            if (memorySection.isNotEmpty() && !memorySection.contains("{memory_context}")) {
                memorySection.lines()
                    .filter { it.trim().startsWith("- ") }
                    .map { it.trim().substring(2) } // Remove "- " prefix
                    .filter { it.isNotEmpty() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error extracting current memory context", e)
            emptyList()
        }
    }

    private fun parseModelResponse(response: String): ModelDecision {
        try {
            val type = response.substringAfter("### Type ###", "").substringBefore("###").trim()
            val reply = response.substringAfter("### Reply ###", "").substringBefore("###").trim()
            val instruction = response.substringAfter("### Instruction ###", "").substringBefore("###").trim()
            val shouldEndStr = response.substringAfter("### Should End ###", "").trim()
            val shouldEnd = shouldEndStr.equals("Finished", ignoreCase = true)

            val finalReply = if (reply.isEmpty() && type.equals("Reply", ignoreCase = true)) {
                "I'm not sure how to respond to that."
            } else {
                reply
            }

            return ModelDecision(type, finalReply, instruction, shouldEnd)
        } catch (e: Exception) {
            Log.e("ConvAgent", "Error parsing custom format, falling back. Response: $response")
            return ModelDecision(reply = "I seem to have gotten my thoughts tangled. Could you repeat that?")
        }
    }
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Conversational Agent")
            .setContentText("Listening for your commands...")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Conversational Agent Service Channel",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    /**
     * Displays a list of futuristic-styled clarification questions at the top of the screen.
     * Each question animates in from the top with a fade-in effect.
     *
     * @param questions The list of question strings to display.
     */
    private fun displayClarificationQuestions(questions: List<String>) {
        mainHandler.post {
            // First, remove any questions that might already be on screen

            val topMargin = 100 // Base margin from the very top of the screen
            val verticalSpacing = 20 // Space between question boxes
            var accumulatedHeight = 0 // Tracks the vertical space used by previous questions

            questions.forEachIndexed { index, questionText ->
                // 1. Create and style the TextView
                val textView = TextView(this).apply {
                    text = questionText
                    // --- (Your existing styling code is perfect, no changes needed here) ---
                    val glowEffect = GradientDrawable(
                        GradientDrawable.Orientation.BL_TR,
                        intArrayOf("#BE63F3".toColorInt(), "#5880F7".toColorInt())
                    ).apply { cornerRadius = 32f }

                    val glassBackground = GradientDrawable(
                        GradientDrawable.Orientation.TL_BR,
                        intArrayOf(0xEE0D0D2E.toInt(), 0xEE2A0D45.toInt())
                    ).apply {
                        cornerRadius = 28f
                        setStroke(1, 0x80FFFFFF.toInt())
                    }

                    val layerDrawable = LayerDrawable(arrayOf(glowEffect, glassBackground)).apply {
                        setLayerInset(1, 4, 4, 4, 4)
                    }
                    background = layerDrawable
                    setTextColor(0xFFE0E0E0.toInt())
                    textSize = 15f
                    setPadding(40, 24, 40, 24)
                    typeface = Typeface.MONOSPACE
                }

                // **--- FIX IS HERE ---**
                // A. Measure the view to get its dimensions *before* positioning.
                textView.measure(
                    View.MeasureSpec.makeMeasureSpec((windowManager.defaultDisplay.width * 0.9).toInt(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                )
                val viewHeight = textView.measuredHeight

                // B. Pre-calculate the final Y position using the current accumulated height.
                val finalYPosition = topMargin + accumulatedHeight

                // C. Update accumulatedHeight for the *next* view in the loop.
                accumulatedHeight += viewHeight + verticalSpacing
                // **--- END OF FIX ---**


                // 2. Prepare layout params
                val params = WindowManager.LayoutParams(
                    (windowManager.defaultDisplay.width * 0.9).toInt(), // 90% of screen width
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    // Initial animation state: off-screen at the top and fully transparent
                    y = -viewHeight // Start above the screen
                    alpha = 0f
                }

                // 3. Add the view and start the animation
                try {
                    windowManager.addView(textView, params)
                    clarificationQuestionViews.add(textView)

                    // Animate the view from its starting position to the calculated finalYPosition
                    val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                        duration = 500L
                        startDelay = (index * 150).toLong() // Stagger animation

                        addUpdateListener { animation ->
                            val progress = animation.animatedValue as Float
                            // Animate Y position from its off-screen start to its final place
                            params.y = (finalYPosition * progress - viewHeight * (1 - progress)).toInt()
                            params.alpha = progress
                            windowManager.updateViewLayout(textView, params)
                        }
                    }
                    animator.start()

                } catch (e: Exception) {
                    Log.e("ConvAgent", "Failed to display futuristic clarification question.", e)
                }
            }
        }
    }

    /**
     * Removes all currently displayed clarification questions from the screen.
     */
    private fun removeClarificationQuestions() {
        mainHandler.post {
            clarificationQuestionViews.forEach { view ->
                if (view.isAttachedToWindow) {
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        Log.e("ConvAgent", "Error removing clarification view.", e)
                    }
                }
            }
            clarificationQuestionViews.clear()
        }
    }

    private suspend fun gracefulShutdown(exitMessage: String? = null) {
        // Track graceful shutdown
        val shutdownBundle = android.os.Bundle().apply {
            putBoolean("had_exit_message", exitMessage != null)
            putInt("conversation_length", conversationHistory.size)
            putBoolean("text_mode_used", isTextModeActive)
            putInt("clarification_attempts", clarificationAttempts)
            putInt("stt_error_attempts", sttErrorAttempts)
        }
        firebaseAnalytics.logEvent("conversation_ended_gracefully", shutdownBundle)
        
        visualFeedbackManager.hideTtsWave()
        visualFeedbackManager.hideTranscription()
        visualFeedbackManager.hideSpeakingOverlay()
        visualFeedbackManager.hideInputBox()

        if (exitMessage != null) {
                speechCoordinator.speakText(exitMessage)
                delay(2000) // Give TTS time to finish
            }
            // 1. Extract memories from the conversation before ending
            if (conversationHistory.size > 1) {
                Log.d("ConvAgent", "Extracting memories before shutdown.")
                MemoryExtractor.extractAndStoreMemories(conversationHistory, memoryManager, usedMemories)
            }
            // 3. Stop the service
            stopSelf()

    }

    /**
     * Immediately stops all TTS, STT, and background tasks, hides all UI, and stops the service.
     * This is used for forceful termination, like an outside tap.
     */
    private suspend fun instantShutdown() {
        // Track instant shutdown
        val instantShutdownBundle = android.os.Bundle().apply {
            putInt("conversation_length", conversationHistory.size)
            putBoolean("text_mode_used", isTextModeActive)
            putInt("clarification_attempts", clarificationAttempts)
            putInt("stt_error_attempts", sttErrorAttempts)
        }
        firebaseAnalytics.logEvent("conversation_ended_instantly", instantShutdownBundle)
        
        Log.d("ConvAgent", "Instant shutdown triggered by user.")
        speechCoordinator.stopSpeaking()
        speechCoordinator.stopListening()
        visualFeedbackManager.hideTtsWave()
        visualFeedbackManager.hideTranscription()
        visualFeedbackManager.hideSpeakingOverlay()
        visualFeedbackManager.hideInputBox()

        removeClarificationQuestions()
        // Make a thread-safe copy of the conversation history.
        if (conversationHistory.size > 1) {
            Log.d("ConvAgent", "Extracting memories before shutdown.")
            MemoryExtractor.extractAndStoreMemories(conversationHistory, memoryManager, usedMemories)
        }
        serviceScope.cancel("User tapped outside, forcing instant shutdown.")

        stopSelf()
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d("ConvAgent", "Service onDestroy")
        
        // Track service destruction
        firebaseAnalytics.logEvent("conversational_agent_destroyed", null)
        
        removeClarificationQuestions()
        serviceScope.cancel()
        ttsManager.setCaptionsEnabled(false)
        isRunning = false
        visualFeedbackManager.hideSpeakingOverlay() // <-- ADD THIS LINE
        // USE the new manager to hide the wave and transcription view
        visualFeedbackManager.hideTtsWave()
        visualFeedbackManager.hideTranscription()
        visualFeedbackManager.hideInputBox()

    }

    override fun onBind(intent: Intent?): IBinder? = null
}