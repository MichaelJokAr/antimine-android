package dev.lucasnlm.antimine

import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.text.format.DateUtils
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import androidx.activity.viewModels
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.HandlerCompat.postDelayed
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentTransaction
import androidx.lifecycle.Observer
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import dev.lucasnlm.antimine.about.AboutActivity
import dev.lucasnlm.antimine.common.level.models.Difficulty
import dev.lucasnlm.antimine.common.level.models.Event
import dev.lucasnlm.antimine.common.level.models.Score
import dev.lucasnlm.antimine.common.level.models.Status
import dev.lucasnlm.antimine.common.level.repository.ISavesRepository
import dev.lucasnlm.antimine.common.level.viewmodel.GameViewModel
import dev.lucasnlm.antimine.control.ControlDialogFragment
import dev.lucasnlm.antimine.core.analytics.AnalyticsManager
import dev.lucasnlm.antimine.core.analytics.models.Analytics
import dev.lucasnlm.antimine.core.preferences.IPreferencesRepository
import dev.lucasnlm.antimine.history.HistoryActivity
import dev.lucasnlm.antimine.instant.InstantAppManager
import dev.lucasnlm.antimine.custom.CustomLevelDialogFragment
import dev.lucasnlm.antimine.level.view.EndGameDialogFragment
import dev.lucasnlm.antimine.level.view.LevelFragment
import dev.lucasnlm.antimine.preferences.PreferencesActivity
import dev.lucasnlm.antimine.share.viewmodel.ShareViewModel
import dev.lucasnlm.antimine.stats.StatsActivity
import kotlinx.android.synthetic.main.activity_game.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class GameActivity : AppCompatActivity(), DialogInterface.OnDismissListener {
    @Inject
    lateinit var preferencesRepository: IPreferencesRepository

    @Inject
    lateinit var analyticsManager: AnalyticsManager

    @Inject
    lateinit var instantAppManager: InstantAppManager

    @Inject
    lateinit var savesRepository: ISavesRepository

    private val viewModel: GameViewModel by viewModels()
    private val shareViewModel: ShareViewModel by viewModels()

    private var status: Status = Status.PreGame
    private val usingLargeArea by lazy { preferencesRepository.useLargeAreas() }
    private var totalMines: Int = 0
    private var totalArea: Int = 0
    private var rightMines: Int = 0
    private var currentTime: Long = 0
    private var currentSaveId: Long = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_game)
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)

        bindViewModel()
        bindToolbar()
        bindDrawer()
        bindNavigationMenu()
        loadGameFragment()

        if (instantAppManager.isEnabled()) {
            bindInstantApp()
            savesRepository.setLimit(1)
        } else {
            checkUseCount()
        }
    }

    private fun bindViewModel() = viewModel.apply {
        var lastEvent: Event? = null // TODO use distinctUntilChanged when available

        eventObserver.observe(
            this@GameActivity,
            Observer {
                if (lastEvent != it) {
                    onGameEvent(it)
                    lastEvent = it
                }
            }
        )

        retryObserver.observe(
            this@GameActivity,
            Observer {
                GlobalScope.launch {
                    viewModel.retryGame(currentSaveId.toInt())
                }
            }
        )

        shareObserver.observe(
            this@GameActivity,
            Observer {
                shareCurrentGame()
            }
        )

        elapsedTimeSeconds.observe(
            this@GameActivity,
            Observer {
                timer.apply {
                    visibility = if (it == 0L) View.GONE else View.VISIBLE
                    text = DateUtils.formatElapsedTime(it)
                }
                currentTime = it
            }
        )

        mineCount.observe(
            this@GameActivity,
            Observer {
                minesCount.apply {
                    visibility = View.VISIBLE
                    text = it.toString()
                }
            }
        )

        difficulty.observe(
            this@GameActivity,
            Observer {
                onChangeDifficulty(it)
            }
        )

        field.observe(
            this@GameActivity,
            Observer { area ->
                val mines = area.filter { it.hasMine }
                totalArea = area.count()
                totalMines = mines.count()
                rightMines = mines.count { it.mark.isFlag() }
            }
        )

        saveId.observe(
            this@GameActivity,
            Observer {
                currentSaveId = it
            }
        )
    }

    override fun onBackPressed() {
        when {
            drawer.isDrawerOpen(GravityCompat.START) -> {
                drawer.closeDrawer(GravityCompat.START)
                viewModel.resumeGame()
            }
            status == Status.Running && instantAppManager.isEnabled() -> showQuitConfirmation {
                super.onBackPressed()
            }
            else -> super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        val willReset = restartIfNeed()

        if (!willReset) {
            if (status == Status.Running) {
                viewModel.run {
                    refreshUserPreferences()
                    resumeGame()
                }

                analyticsManager.sentEvent(Analytics.Resume)
            }
        }
    }

    override fun onPause() {
        super.onPause()

        if (status == Status.Running) {
            viewModel.pauseGame()
        }

        analyticsManager.sentEvent(Analytics.Quit)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean =
        when (status) {
            is Status.Over, is Status.Running -> {
                menuInflater.inflate(R.menu.top_menu_over, menu)
                true
            }
            else -> true
        }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return if (item.itemId == R.id.reset) {

            val confirmResign = status == Status.Running
            analyticsManager.sentEvent(Analytics.TapGameReset(confirmResign))

            if (confirmResign) {
                newGameConfirmation {
                    GlobalScope.launch {
                        viewModel.startNewGame()
                    }
                }
            } else {
                GlobalScope.launch {
                    viewModel.startNewGame()
                }
            }
            true
        } else {
            super.onOptionsItemSelected(item)
        }
    }

    private fun bindToolbar() {
        setSupportActionBar(toolbar)
        toolbar.title = ""

        supportActionBar?.apply {
            title = ""
            setDisplayHomeAsUpEnabled(true)
            setHomeButtonEnabled(true)
        }
    }

    private fun bindDrawer() {
        drawer.apply {
            addDrawerListener(
                ActionBarDrawerToggle(
                    this@GameActivity,
                    drawer,
                    toolbar,
                    R.string.open_menu,
                    R.string.close_menu
                ).apply {
                    syncState()
                }
            )

            addDrawerListener(object : DrawerLayout.DrawerListener {
                override fun onDrawerSlide(drawerView: View, slideOffset: Float) {
                    // Empty
                }

                override fun onDrawerOpened(drawerView: View) {
                    viewModel.pauseGame()
                    analyticsManager.sentEvent(Analytics.OpenDrawer)
                }

                override fun onDrawerClosed(drawerView: View) {
                    if (hasNoOtherFocusedDialog()) {
                        viewModel.resumeGame()
                    }

                    analyticsManager.sentEvent(Analytics.CloseDrawer)
                }

                override fun onDrawerStateChanged(newState: Int) {
                    // Empty
                }
            })

            if (preferencesRepository.getBoolean(PREFERENCE_FIRST_USE, false)) {
                openDrawer(GravityCompat.START)
                preferencesRepository.putBoolean(PREFERENCE_FIRST_USE, true)
            }
        }
    }

    private fun bindNavigationMenu() {
        navigationView.setNavigationItemSelectedListener { item ->
            var handled = true

            when (item.itemId) {
                R.id.standard -> changeDifficulty(Difficulty.Standard)
                R.id.beginner -> changeDifficulty(Difficulty.Beginner)
                R.id.intermediate -> changeDifficulty(Difficulty.Intermediate)
                R.id.expert -> changeDifficulty(Difficulty.Expert)
                R.id.custom -> showCustomLevelDialog()
                R.id.control -> showControlDialog()
                R.id.about -> showAbout()
                R.id.settings -> showSettings()
                R.id.rate -> openRateUsLink("Drawer")
                R.id.share_now -> shareCurrentGame()
                R.id.previous_games -> openSaveHistory()
                R.id.stats -> openStats()
                R.id.install_new -> installFromInstantApp()
                else -> handled = false
            }

            if (handled) {
                drawer.closeDrawer(GravityCompat.START)
            }

            handled
        }

        navigationView.menu.findItem(R.id.share_now).isVisible = instantAppManager.isNotEnabled()
    }

    private fun checkUseCount() {
        val current = preferencesRepository.getInt(PREFERENCE_USE_COUNT, 0)
        val shouldRequestRating = preferencesRepository.getBoolean(PREFERENCE_REQUEST_RATING, true)

        if (current >= MIN_USAGES_TO_RATING && shouldRequestRating) {
            analyticsManager.sentEvent(Analytics.ShowRatingRequest(current))
            showRequestRating()
        }

        preferencesRepository.putInt(PREFERENCE_USE_COUNT, current + 1)
    }

    private fun onChangeDifficulty(difficulty: Difficulty) {
        navigationView.menu.apply {
            arrayOf(
                Difficulty.Standard to findItem(R.id.standard),
                Difficulty.Beginner to findItem(R.id.beginner),
                Difficulty.Intermediate to findItem(R.id.intermediate),
                Difficulty.Expert to findItem(R.id.expert),
                Difficulty.Custom to findItem(R.id.custom)
            ).map {
                it.second to (if (it.first == difficulty) R.drawable.checked else R.drawable.unchecked)
            }.forEach { (menuItem, icon) ->
                menuItem.setIcon(icon)
            }
        }
    }

    private fun loadGameFragment() {
        val fragmentManager = supportFragmentManager

        fragmentManager.popBackStack()

        fragmentManager.findFragmentById(R.id.levelContainer)?.let { it ->
            fragmentManager.beginTransaction().apply {
                remove(it)
                commitAllowingStateLoss()
            }
        }

        fragmentManager.beginTransaction().apply {
            replace(R.id.levelContainer, LevelFragment())
            setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            commitAllowingStateLoss()
        }
    }

    private fun showRequestRating() {
        if (getString(R.string.rating_message).isNotEmpty()) {

            AlertDialog.Builder(this)
                .setTitle(R.string.rating)
                .setMessage(R.string.rating_message)
                .setPositiveButton(R.string.yes) { _, _ ->
                    openRateUsLink("Dialog")
                }
                .setNegativeButton(R.string.rating_button_no) { _, _ ->
                    preferencesRepository.putBoolean(PREFERENCE_REQUEST_RATING, false)
                }
                .show()
        }
    }

    private fun newGameConfirmation(action: () -> Unit) {
        AlertDialog.Builder(this, R.style.MyDialog).apply {
            setTitle(R.string.new_game)
            setMessage(R.string.retry_sure)
            setPositiveButton(R.string.resume) { _, _ -> action() }
            setNegativeButton(R.string.cancel, null)
            show()
        }
    }

    private fun showQuitConfirmation(action: () -> Unit) {
        AlertDialog.Builder(this, R.style.MyDialog)
            .setTitle(R.string.are_you_sure)
            .setMessage(R.string.quit_confirm)
            .setPositiveButton(R.string.quit) { _, _ -> action() }
            .setNeutralButton(R.string.install) { _, _ -> installFromInstantApp() }
            .show()
    }

    private fun showCustomLevelDialog() {
        if (supportFragmentManager.findFragmentByTag(CustomLevelDialogFragment.TAG) == null) {
            CustomLevelDialogFragment().apply {
                show(supportFragmentManager, CustomLevelDialogFragment.TAG)
            }
        }
    }

    private fun showControlDialog() {
        viewModel.pauseGame()

        if (supportFragmentManager.findFragmentByTag(CustomLevelDialogFragment.TAG) == null) {
            ControlDialogFragment().apply {
                show(supportFragmentManager, ControlDialogFragment.TAG)
            }
        }
    }

    private fun showAbout() {
        analyticsManager.sentEvent(Analytics.OpenAbout)
        Intent(this, AboutActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun openSaveHistory() {
        analyticsManager.sentEvent(Analytics.OpenSaveHistory)
        Intent(this, HistoryActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun openStats() {
        analyticsManager.sentEvent(Analytics.OpenStats)
        Intent(this, StatsActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun showSettings() {
        analyticsManager.sentEvent(Analytics.OpenSettings)
        Intent(this, PreferencesActivity::class.java).apply {
            startActivity(this)
        }
    }

    private fun showEndGameDialog(victory: Boolean) {
        val currentGameStatus = status
        if (currentGameStatus is Status.Over && !isFinishing && !drawer.isDrawerOpen(GravityCompat.START)) {
            if (supportFragmentManager.findFragmentByTag(EndGameDialogFragment.TAG) == null) {
                val score = currentGameStatus.score
                EndGameDialogFragment.newInstance(
                    victory,
                    score?.rightMines ?: 0,
                    score?.totalMines ?: 0,
                    currentGameStatus.time
                ).apply {
                    showAllowingStateLoss(supportFragmentManager, EndGameDialogFragment.TAG)
                }
            }
        }
    }

    private fun waitAndShowEndGameDialog(victory: Boolean, await: Boolean) {
        if (await && viewModel.explosionDelay() != 0L) {
            postDelayed(
                Handler(),
                {
                    showEndGameDialog(victory)
                },
                null, (viewModel.explosionDelay() * 0.3).toLong()
            )
        } else {
            showEndGameDialog(victory)
        }
    }

    private fun changeDifficulty(newDifficulty: Difficulty) {
        if (status == Status.PreGame) {
            GlobalScope.launch {
                viewModel.startNewGame(newDifficulty)
            }
        } else {
            newGameConfirmation {
                GlobalScope.launch {
                    viewModel.startNewGame(newDifficulty)
                }
            }
        }
    }

    private fun onGameEvent(event: Event) {
        when (event) {
            Event.ResumeGame -> {
                status = Status.Running
                invalidateOptionsMenu()
            }
            Event.StartNewGame -> {
                status = Status.PreGame
                invalidateOptionsMenu()
            }
            Event.Resume, Event.Running -> {
                status = Status.Running
                viewModel.runClock()
                invalidateOptionsMenu()
                keepScreenOn(true)
            }
            Event.Victory -> {
                val score = Score(
                    rightMines,
                    totalMines,
                    totalArea
                )
                status = Status.Over(currentTime, score)
                viewModel.stopClock()
                viewModel.revealAllEmptyAreas()
                viewModel.victory()
                invalidateOptionsMenu()
                keepScreenOn(false)
                waitAndShowEndGameDialog(
                    victory = true,
                    await = false
                )
            }
            Event.GameOver -> {
                val isResuming = (status == Status.PreGame)
                val score = Score(
                    rightMines,
                    totalMines,
                    totalArea
                )
                status = Status.Over(currentTime, score)
                invalidateOptionsMenu()
                keepScreenOn(false)
                viewModel.stopClock()

                GlobalScope.launch(context = Dispatchers.Main) {
                    viewModel.gameOver(isResuming)
                    waitAndShowEndGameDialog(
                        victory = false,
                        await = true
                    )
                }
            }
            else -> { }
        }
    }

    /**
     * If user change any accessibility preference, the game will restart the activity to
     * apply these changes.
     */
    private fun restartIfNeed(): Boolean {
        return (usingLargeArea != preferencesRepository.useLargeAreas()).also {
            if (it) {
                recreate()
            }
        }
    }

    private fun shareCurrentGame() {
        val levelSetup = viewModel.levelSetup.value
        val field = viewModel.field.value
        GlobalScope.launch {
            shareViewModel.share(levelSetup, field)
        }
    }

    private fun bindInstantApp() {
        findViewById<View>(R.id.install).apply {
            visibility = View.VISIBLE
            setOnClickListener {
                installFromInstantApp()
            }
        }

        navigationView.menu.setGroupVisible(R.id.install_group, true)
    }

    private fun installFromInstantApp() {
        instantAppManager.showInstallPrompt(this@GameActivity, null, IA_REQUEST_CODE, IA_REFERRER)
    }

    private fun openRateUsLink(from: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")))
        } catch (e: ActivityNotFoundException) {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                )
            )
        }

        analyticsManager.sentEvent(Analytics.TapRatingRequest(from))
        preferencesRepository.putBoolean(PREFERENCE_REQUEST_RATING, false)
    }

    private fun keepScreenOn(enabled: Boolean) {
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun hasNoOtherFocusedDialog(): Boolean {
        return supportFragmentManager.fragments.count {
            it !is LevelFragment && it is DialogFragment
        } == 0
    }

    override fun onDismiss(dialog: DialogInterface?) {
        viewModel.run {
            refreshUserPreferences()
            resumeGame()
        }
    }

    companion object {
        const val PREFERENCE_FIRST_USE = "preference_first_use"
        const val PREFERENCE_USE_COUNT = "preference_use_count"
        const val PREFERENCE_REQUEST_RATING = "preference_request_rating"

        const val IA_REFERRER = "InstallApiActivity"
        const val IA_REQUEST_CODE = 5

        const val MIN_USAGES_TO_RATING = 4
    }
}
