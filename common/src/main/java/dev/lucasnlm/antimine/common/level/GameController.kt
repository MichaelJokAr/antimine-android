package dev.lucasnlm.antimine.common.level

import dev.lucasnlm.antimine.common.level.database.models.FirstOpen
import dev.lucasnlm.antimine.common.level.database.models.Save
import dev.lucasnlm.antimine.common.level.database.models.SaveStatus
import dev.lucasnlm.antimine.common.level.database.models.Stats
import dev.lucasnlm.antimine.common.level.models.Area
import dev.lucasnlm.antimine.common.level.models.Difficulty
import dev.lucasnlm.antimine.common.level.models.Mark
import dev.lucasnlm.antimine.common.level.models.Minefield
import dev.lucasnlm.antimine.common.level.models.Score
import dev.lucasnlm.antimine.core.control.ActionResponse
import dev.lucasnlm.antimine.core.control.ActionFeedback
import dev.lucasnlm.antimine.core.control.GameControl
import kotlin.math.floor
import kotlin.random.Random

/**
 * Controls a minesweeper logic.
 */
class GameController {
    private val minefield: Minefield
    private val randomGenerator: Random
    private val startTime = System.currentTimeMillis()
    private var saveId = 0
    private var firstOpen: FirstOpen = FirstOpen.Unknown
    private var gameControl: GameControl = GameControl.Standard
    private var mines: Sequence<Area> = emptySequence()
    private var useQuestionMark = true

    var hasMines = false
        private set

    val seed: Long

    lateinit var field: Sequence<Area>
        private set

    constructor(minefield: Minefield, seed: Long, saveId: Int? = null) {
        this.minefield = minefield
        this.randomGenerator = Random(seed)
        this.seed = seed
        this.saveId = saveId ?: 0
        createEmptyField()
    }

    constructor(save: Save) {
        this.minefield = save.minefield
        this.randomGenerator = Random(save.seed)
        this.saveId = save.uid
        this.seed = save.seed
        this.firstOpen = save.firstOpen

        this.field = save.field.asSequence()
        this.mines = this.field.filter { it.hasMine }.asSequence()
        this.hasMines = this.mines.count() != 0
    }

    private fun createEmptyField() {
        val width = minefield.width
        val height = minefield.height
        val fieldSize = width * height

        hasMines = false
        mines = emptySequence()
        field = (0 until fieldSize).map { index ->
            val yPosition = floor((index / width).toDouble()).toInt()
            val xPosition = (index % width)
            Area(index, xPosition, yPosition)
        }.asSequence()
    }

    fun getArea(id: Int) = field.first { it.id == id }

    fun plantMinesExcept(index: Int, includeSafeArea: Boolean = false) {
        plantRandomMines(index, includeSafeArea)
        putMinesTips()
    }

    private fun plantRandomMines(safeIndex: Int, includeSafeArea: Boolean) {
        getArea(safeIndex).run {
            safeZone = true

            if (includeSafeArea) {
                findNeighbors().forEach {
                    it.safeZone = true
                }

                if (minefield.width > 9) {
                    findCrossNeighbors().forEach { neighbor ->
                        neighbor
                            .findCrossNeighbors()
                            .filterNot { it.safeZone }
                            .forEach { it.safeZone = true }
                    }
                }
            }
        }

        firstOpen = FirstOpen.Position(safeIndex)
        field.filterNot { it.safeZone }
            .toSet()
            .shuffled(randomGenerator)
            .take(minefield.mines)
            .forEach { it.hasMine = true }
        mines = field.filter { it.hasMine }
        hasMines = mines.count() != 0
    }

    private fun putMinesTips() {
        field.forEach {
            it.minesAround = if (it.hasMine) 0 else it.findNeighbors().count { neighbor ->
                neighbor.hasMine
            }
        }
    }

    /**
     * Disable all highlighted areas.
     * @return the number of changed tiles.
     */
    fun turnOffAllHighlighted(): Int {
        return field.filter { it.highlighted }.run {
            forEach { it.highlighted = false }
            count()
        }
    }

    fun singleClick(index: Int): ActionFeedback = getArea(index).run {
        return runActionOn(
            if (isCovered) gameControl.onCovered.singleClick else gameControl.onOpen.singleClick
        )
    }

    fun doubleClick(index: Int): ActionFeedback = getArea(index).run {
        return runActionOn(
            if (isCovered) gameControl.onCovered.doubleClick else gameControl.onOpen.doubleClick
        )
    }

    fun longPress(index: Int): ActionFeedback = getArea(index).run {
        return runActionOn(
            if (isCovered) gameControl.onCovered.longPress else gameControl.onOpen.longPress
        )
    }

    fun runFlagAssistant(): Sequence<Area> {
        // Must not select Mark.PurposefulNone, only Mark.None. Otherwise, it will flag
        // a square that was previously unflagged by player.
        val assists = mutableListOf<Area>()

        mines.filter { it.mark.isPureNone() }.forEach { field ->
            val neighbors = field.findNeighbors()
            val neighborsCount = neighbors.count()
            val revealedNeighborsCount = neighbors.count { neighbor ->
                !neighbor.isCovered || (neighbor.hasMine && neighbor.mark.isFlag())
            }

            if (revealedNeighborsCount == neighborsCount) {
                assists.add(field)
                field.mark = Mark.Flag
            } else {
                field.mark = Mark.None
            }
        }

        return assists.asSequence()
    }

    fun getScore() = Score(
        mines.count { !it.mistake && it.mark.isFlag() },
        mines.count(),
        field.count()
    )

    fun getMinesCount() = mines.count()

    fun showAllMines() =
        mines.filter { it.mark != Mark.Flag }.forEach { it.isCovered = false }

    fun findExplodedMine() = mines.filter { it.mistake }.firstOrNull()

    fun takeExplosionRadius(target: Area): Sequence<Area> =
        mines.filter { it.isCovered && it.mark.isNone() }.sortedBy {
            val dx1 = (it.posX - target.posX)
            val dy1 = (it.posY - target.posY)
            dx1 * dx1 + dy1 * dy1
        }

    fun flagAllMines() = mines.forEach { it.mark = Mark.Flag }

    fun showWrongFlags() = field.filter { it.mark.isNotNone() && !it.hasMine }.forEach { it.mistake = true }

    fun revealAllEmptyAreas() = field.filterNot { it.hasMine }.forEach { it.isCovered = false }

    fun hasAnyMineExploded(): Boolean = mines.firstOrNull { it.mistake } != null

    fun hasFlaggedAllMines(): Boolean = rightFlags() == minefield.mines

    fun hasIsolatedAllMines() =
        mines.map {
            val neighbors = it.findNeighbors()
            val neighborsCount = neighbors.count()
            val isolatedNeighborsCount = neighbors.count { neighbor ->
                !neighbor.isCovered || neighbor.hasMine
            }
            neighborsCount != isolatedNeighborsCount
        }.count { it } == 0

    private fun rightFlags() = mines.count { it.mark.isFlag() }

    fun checkVictory(): Boolean =
        hasMines && hasIsolatedAllMines() && !hasAnyMineExploded()

    fun isGameOver(): Boolean =
        checkVictory() || hasAnyMineExploded()

    fun remainingMines(): Int {
        val flagsCount = field.count { it.mark.isFlag() }
        val minesCount = mines.count()
        return (minesCount - flagsCount).coerceAtLeast(0)
    }

    fun getSaveState(duration: Long, difficulty: Difficulty): Save {
        val saveStatus: SaveStatus = when {
            checkVictory() -> SaveStatus.VICTORY
            hasAnyMineExploded() -> SaveStatus.DEFEAT
            else -> SaveStatus.ON_GOING
        }
        return Save(
            saveId,
            seed,
            startTime,
            duration,
            minefield,
            difficulty,
            firstOpen,
            saveStatus,
            field.toList()
        )
    }

    fun getStats(duration: Long): Stats? {
        val gameStatus: SaveStatus = when {
            checkVictory() -> SaveStatus.VICTORY
            hasAnyMineExploded() -> SaveStatus.DEFEAT
            else -> SaveStatus.ON_GOING
        }
        return if (gameStatus == SaveStatus.ON_GOING) {
            null
        } else {
            Stats(
                0,
                duration,
                mines.count(),
                if (gameStatus == SaveStatus.VICTORY) 1 else 0,
                minefield.width,
                minefield.height,
                mines.count { !it.isCovered }
            )
        }
    }

    fun setCurrentSaveId(id: Int) {
        this.saveId = id.coerceAtLeast(0)
    }

    fun updateGameControl(newGameControl: GameControl) {
        this.gameControl = newGameControl
    }

    fun useQuestionMark(useQuestionMark: Boolean) {
        this.useQuestionMark = useQuestionMark
    }

    /**
     * Run a game [actionResponse] on a given tile.
     * @return The number of changed tiles.
     */
    private fun Area.runActionOn(actionResponse: ActionResponse?): ActionFeedback {
        val highlightedChanged = turnOffAllHighlighted()

        val changed = when (actionResponse) {
            ActionResponse.OpenTile -> {
                if (!hasMines) {
                    plantMinesExcept(id, true)
                }

                if (mark.isNotNone()) {
                    mark = Mark.PurposefulNone
                    1
                } else {
                    openTile()
                }
            }
            ActionResponse.SwitchMark -> {
                if (!hasMines) {
                    plantMinesExcept(id, true)
                    openTile()
                } else if (isCovered) {
                    switchMark()
                    1
                } else 0
            }
            ActionResponse.HighlightNeighbors -> {
                if (minesAround != 0) highlight() else 0
            }
            ActionResponse.OpenNeighbors -> {
                openNeighbors()
                8
            }
            else -> 0
        }

        return ActionFeedback(actionResponse, id, (changed + highlightedChanged) > 1)
    }

    fun Area.switchMark(): Area = apply {
        if (isCovered) {
            mark = when (mark) {
                Mark.PurposefulNone, Mark.None -> Mark.Flag
                Mark.Flag -> if (useQuestionMark) Mark.Question else Mark.None
                Mark.Question -> Mark.None
            }
        }
    }

    fun Area.removeMark() =
        this.apply {
            mark = Mark.PurposefulNone
        }

    /**
     * Run "Flood Fill algorithm" to open all empty neighbors of a target area.
     */
    fun Area.openTile(): Int {
        var changes = 0
        run {
            if (isCovered) {
                changes += 1
                isCovered = false
                mark = Mark.None

                if (hasMine) {
                    mistake = true
                } else if (minesAround == 0) {
                    findNeighbors()
                        .filter { it.isCovered }
                        .also {
                            changes += it.count()
                        }
                        .forEach { it.openTile() }
                }
            }
        }
        return changes
    }

    private fun Area.highlight(): Int = run {
        return when {
            minesAround != 0 -> {
                this.toggleHighlight()
            }
            else -> 0
        }
    }

    private fun Area.toggleHighlight(): Int {
        var changed = 1
        apply {
            highlighted = !highlighted
            findNeighbors()
                .filter { it.mark.isNone() && it.isCovered }
                .also { changed += it.count() }
                .forEach { it.highlighted = !it.highlighted }
        }
        return changed
    }

    fun Area.openNeighbors(): Int {
        val neighbors = findNeighbors()
        val flaggedCount = neighbors.count { it.mark.isFlag() }
        return if (flaggedCount >= minesAround) {
            neighbors
                .filter {
                    it.mark.isNone() && it.isCovered
                }.also {
                    it.forEach { area -> area.openTile() }
                }.count()
        } else {
            0
        }
    }

    fun Area.findNeighbors() = sequenceOf(
        1 to 0, 1 to 1, 0 to 1, -1 to 1, -1 to 0, -1 to -1, 0 to -1, 1 to -1
    ).map { (x, y) -> getNeighbor(x, y) }.filterNotNull()

    private fun Area.findCrossNeighbors() = sequenceOf(
        1 to 0, 0 to 1, -1 to 0, 0 to -1
    ).map { (x, y) -> getNeighbor(x, y) }.filterNotNull()

    private fun Area.getNeighbor(x: Int, y: Int) = field.firstOrNull {
        (it.posX == this.posX + x) && (it.posY == this.posY + y)
    }
}
