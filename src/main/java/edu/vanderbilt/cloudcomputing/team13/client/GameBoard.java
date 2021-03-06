/**
 * Created by Killian on 10/31/17.
 */
package edu.vanderbilt.cloudcomputing.team13.client;

import java.awt.*;
import java.io.InputStream;
import java.io.File;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.sun.xml.internal.bind.v2.TODO;
import edu.vanderbilt.cloudcomputing.team13.util.GraphicUtils;
import edu.vanderbilt.cloudcomputing.team13.util.Player;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;


public class GameBoard {
    // use this bi-directional interface to communicate with client
    GraphicInterface graphicInterface;

    // game specs
    private GameState gameState = null;

    // The window handle
    private long window;
    private int windowWidth = 800;
    private int windowHeight = 600;

    // player display frame center, player 0, centerY -> playerFrameCenter[0][1]
    private double[][] playerFrameCenter = null;
    private double playerFrameSideWidth = 0.0;
    private double readyButtonWidth = 80.0;
    private double readyButtonSpace = 300.0;
    private double gameFrameOffset = 20;
    private double[][] drawableRegion = null;

    // isMouseClicked
    private boolean isMouseClicked = false;

    private double smoothThreshold = 1;

    // a set of points that the player has drawn
    private List<Double> drawnPoints = new ArrayList<>();

    public GameBoard(GraphicInterface graphicInterface, GameState gameState){
        this.graphicInterface = graphicInterface;
        this.gameState = gameState;
        this.playerFrameCenter = new double[gameState.getMAX_PLAYER()][2];

        //gameState.addPlayer("127.0.0.1", "cathy", "0001");
        //gameState.addPlayer("127.0.0.1", "unays", "0002");
    }

    public void run() {
        System.out.println("Game Board started!");

        init();
        loop();

        // Free the window callbacks and destroy the window
        glfwFreeCallbacks(window);
        glfwDestroyWindow(window);

        // Terminate GLFW and free the error callback
        glfwTerminate();
        glfwSetErrorCallback(null).free();
    }

    private void init() {
        // Setup an error callback. The default implementation
        // will print the error message in System.err.
        GLFWErrorCallback.createPrint(System.err).set();

        // Initialize GLFW. Most GLFW functions will not work before doing this.
        if ( !glfwInit() )
            throw new IllegalStateException("Unable to initialize GLFW");

        // Configure GLFW
        glfwDefaultWindowHints(); // optional, the current window hints are already the default
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE); // the window will stay hidden after creation
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE); // the window will be resizable

        // Create the window
        window = glfwCreateWindow(windowWidth, windowHeight, "Let's Draw And Guess!", NULL, NULL);
        if ( window == NULL )
            throw new RuntimeException("Failed to create the GLFW window");

        // Setup a key callback. It will be called every time a key is pressed, repeated or released.
        glfwSetKeyCallback(window, (window, key, scancode, action, mods) -> {
            if ( key == GLFW_KEY_ESCAPE && action == GLFW_RELEASE )
                glfwSetWindowShouldClose(window, true); // We will detect this in the rendering loop
        });

        // Setup a cursor position callback, it will be called every time the cursor is moved
        glfwSetCursorPosCallback(window, (window, xpos, ypos) -> {
            if(gameState.isDrawer() && withinDrawableRegion(xpos, ypos) && isMouseClicked){
                addPointToDrawnList(xpos, ypos);
                reportDrawnPoint(xpos, ypos);
                //System.out.printf("%f, %f\n", xpos, ypos);
            }
        });

        // Setup a cursor button input callback, it will be called every time the cursor is clicked
        glfwSetMouseButtonCallback(window, (window, button, action, mods) -> {
            // get cursor position,
            DoubleBuffer posX = BufferUtils.createDoubleBuffer(1);
            DoubleBuffer posY = BufferUtils.createDoubleBuffer(1);
            glfwGetCursorPos(window, posX, posY);
            // set/cancel ready if clicked ready
            if(button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS && withinReadyButtonRegion(posX.get(0), posY.get(0))){
                boolean readyState = gameState.getPlayerMyself().isReady();
                gameState.getPlayerMyself().setReady(!readyState);
                graphicInterface.reportPlayerReady(gameState.getPlayerId(),!readyState);
                return;
            }

            if(gameState.isDrawer()){
                //drop this callback if it's not in drawable region
                if(!withinDrawableRegion(posX.get(0), posY.get(0))) {
                    return;
                }

                if(button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS){
                    isMouseClicked = true;
                    addSeparatorToDrawnList();
                    reportDrawnPoint(Double.MAX_VALUE, Double.MAX_VALUE);
                }else if(button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE){
                    isMouseClicked = false;
                    addSeparatorToDrawnList();
                    reportDrawnPoint(Double.MAX_VALUE, Double.MAX_VALUE);
                }
            }
        });

        // Get the thread stack and push a new frame
        try ( MemoryStack stack = stackPush() ) {
            IntBuffer pWidth = stack.mallocInt(1); // int*
            IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight);

            // Get the resolution of the primary monitor
            GLFWVidMode vidmode = glfwGetVideoMode(glfwGetPrimaryMonitor());

            // Center the window
            glfwSetWindowPos(
                    window,
                    (vidmode.width() - pWidth.get(0)) / 2,
                    (vidmode.height() - pHeight.get(0)) / 2
            );
        } // the stack frame is popped automatically

        // Make the OpenGL context current
        glfwMakeContextCurrent(window);
        // Enable v-sync
        glfwSwapInterval(1);

        // Make the window visible
        glfwShowWindow(window);
    }

    private void loop() {
        // This line is critical for LWJGL's interoperation with GLFW's
        // OpenGL context, or any context that is managed externally.
        // LWJGL detects the context that is current in the current thread,
        // creates the GLCapabilities instance and makes the OpenGL
        // bindings available for use.
        GL.createCapabilities();

        glOrtho( 0, windowWidth, 0, windowHeight, -1, 1);

        // Set the clear color
        glClearColor( 1f, 1f, 1f, 1.0f);
        drawnPoints.clear();
        initPlayerFrameCenter();
        initDrawableRegion();

        // Run the rendering loop until the user has attempted to close
        // the window or has pressed the ESCAPE key.
        while ( !glfwWindowShouldClose(window) ) {
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // clear the framebuffer

            //myFont.drawString(100, 50, "THE LIGHTWEIGHT JAVA GAMES LIBRARY", Color.yellow);
            //GraphicUtils.drawString("Hello world !", 400,300);
            glEnable(GL_LINE_WIDTH);
            glLineWidth(2);
            renderGameFrame();
            renderPlayerFrame();
            renderDrawableRegionFrame();
            renderDrawing();
            glDisable(GL_LINE_WIDTH);

            renderPlayerInfo();
            if(gameState.isGameOn()) renderGameHelper();
            else renderReadyButton();

            glfwSwapBuffers(window); // swap the color buffers

            // Poll for window events. The key callback above will only be
            // invoked during this call.
            glfwPollEvents();

        }
    }

    public void addPointToDrawnList(double x, double y){
        if(drawnPoints.size() >= 2){
            Double prevX = drawnPoints.get(drawnPoints.size() - 2);
            Double prevY = drawnPoints.get(drawnPoints.size() - 1);
            if(prevX != null && prevY != null) addMakeupPoints(prevX, prevY, x, y);
        }
        drawnPoints.add(x);
        drawnPoints.add(y);
    }

    public void addSeparatorToDrawnList(){
        drawnPoints.add(null);
        drawnPoints.add(null);
    }

    private void addMakeupPoints(double prevX, double prevY, double curX, double curY){
        int num = 0;
        double dist = Math.sqrt(Math.pow(Math.abs(prevX - curX),2) + Math.pow(Math.abs(prevY - curY), 2));
        if(dist > smoothThreshold){
            num = (int) Math.floor(dist / smoothThreshold);
        }
        if(num == 0) return;
        double diffX = (curX - prevX) / num;
        double diffY = (curY - prevY) / num;
        for(int i=1; i <= num -1; i++){
            drawnPoints.add(prevX + diffX * i);
            drawnPoints.add(prevY + diffY * i);
        }
    }

    private void reportDrawnPoint(double x, double y){
        graphicInterface.reportDrawnPoint(x, y);
    }

    private void initPlayerFrameCenter(){
        int maxPlayerNum = playerFrameCenter.length;
        double widthMax = windowWidth - readyButtonSpace;
        playerFrameSideWidth = widthMax / maxPlayerNum;
        for(int i=0; i<maxPlayerNum; i++){
            playerFrameCenter[i][0] = gameFrameOffset + playerFrameSideWidth / 2  + playerFrameSideWidth * i;
            playerFrameCenter[i][1] = - gameFrameOffset + windowHeight - playerFrameSideWidth / 2;
        }
    }

    private void initDrawableRegion(){
        drawableRegion = new double[4][2];
        drawableRegion[0][0] = gameFrameOffset;
        drawableRegion[0][1] = gameFrameOffset;
        drawableRegion[1][0] = windowWidth - gameFrameOffset;
        drawableRegion[1][1] = gameFrameOffset;
        drawableRegion[2][0] = windowWidth - gameFrameOffset;
        drawableRegion[2][1] = windowHeight - gameFrameOffset - playerFrameSideWidth;
        drawableRegion[3][0] = gameFrameOffset;
        drawableRegion[3][1] = windowHeight - gameFrameOffset - playerFrameSideWidth;
    }

    private void renderOnePoint(double x, double y){
        double centerX = x;
        double centerY = windowHeight - y;
        int offSet = 2; // size of the point
        glBegin(GL_QUADS);
        glVertex2d(centerX - offSet, centerY + offSet);
        glVertex2d(centerX + offSet, centerY + offSet);
        glVertex2d(centerX + offSet, centerY - offSet);
        glVertex2d(centerX - offSet, centerY - offSet);
        glEnd();
    }

    private void renderDrawing(){
        for(int i =0; i<drawnPoints.size(); i+=2){
            if(drawnPoints.get(i) == null) continue;
            renderOnePoint(drawnPoints.get(i), drawnPoints.get(i+1));
        }
    }

    private void renderSquareLineLoop(double centerX, double centerY, double r){
        renderRecLineLoop(centerX, centerY, r, r);
        /*
        centerY = windowHeight - centerY;
        glColor3f(0, 0, 0);
        glBegin(GL_LINE_LOOP);
        glVertex2d( centerX - r, centerY + r);
        glVertex2d( centerX + r, centerY + r);
        glVertex2d( centerX + r, centerY - r);
        glVertex2d( centerX - r, centerY - r);
        glEnd();
        */
    }

    private void renderRecLineLoop(double centerX, double centerY, double offsetX, double offsetY){
        centerY = windowHeight - centerY;
        glColor3f(0, 0, 0);
        glBegin(GL_LINE_LOOP);
        glVertex2d( centerX - offsetX, centerY + offsetY);
        glVertex2d( centerX + offsetX, centerY + offsetY);
        glVertex2d( centerX + offsetX, centerY - offsetY);
        glVertex2d( centerX - offsetX, centerY - offsetY);
        glEnd();
    }

    private void renderGameFrame(){
        // general game frame
        double offset = gameFrameOffset;
        glColor3f(0, 0, 0);
        glBegin(GL_LINE_LOOP);
        glVertex2d( 0+offset, 0+offset );
        glVertex2d( 0+offset, windowHeight-offset );
        glVertex2d( windowWidth-offset, windowHeight-offset );
        glVertex2d( windowWidth-offset, 0+offset );
        glEnd();
    }

    private void renderDrawableRegionFrame(){
        glColor3f(0, 0, 0);
        glBegin(GL_LINE_LOOP);
        glVertex2d( drawableRegion[0][0], windowHeight - drawableRegion[0][1]);
        glVertex2d( drawableRegion[1][0], windowHeight - drawableRegion[1][1]);
        glVertex2d( drawableRegion[2][0], windowHeight - drawableRegion[2][1]);
        glVertex2d( drawableRegion[3][0], windowHeight - drawableRegion[3][1]);
        glEnd();
    }

    private void renderPlayerFrame(){
        for(int i=0; i<playerFrameCenter.length; i++){
            renderSquareLineLoop(playerFrameCenter[i][0], playerFrameCenter[i][1], playerFrameSideWidth/2);
        }
    }

    private void renderPlayerInfo(){
        for(Map.Entry<String, Player> entry : gameState.getPlayersMap().entrySet()){
            Player player = entry.getValue();
            int pos = player.getPosition();
            String name = player.getName();
            int points = player.getPoints();
            boolean ready = player.isReady();
            // name
            GraphicUtils.drawString(
                    name,
                    (int) playerFrameCenter[pos][0],
                    windowHeight - (int) playerFrameCenter[pos][1] + 30
            );

            // points
            GraphicUtils.drawString(
                    Integer.toString(points),
                    (int) playerFrameCenter[pos][0],
                    windowHeight - (int) playerFrameCenter[pos][1] + 8
            );

            // ready state
            if(!gameState.isGameOn()){
                if(ready){
                    GraphicUtils.drawString(
                            "ready",
                            (int) playerFrameCenter[pos][0],
                            windowHeight - (int) playerFrameCenter[pos][1] - 20,
                            0,1,0
                    );
                }else{
                    GraphicUtils.drawString(
                            "Not",
                            (int) playerFrameCenter[pos][0],
                            windowHeight - (int) playerFrameCenter[pos][1] - 16,
                            1,0,0
                    );
                    GraphicUtils.drawString(
                            "Ready",
                            (int) playerFrameCenter[pos][0],
                            windowHeight - (int) playerFrameCenter[pos][1] - 30,
                            1,0,0
                    );
                }
            }else{
                // guesser drawer state
                boolean isDrawer = gameState.getDrawerId().equals(player.getId());
                if(isDrawer){
                    GraphicUtils.drawString(
                            "drawer",
                            (int) playerFrameCenter[pos][0],
                            windowHeight - (int) playerFrameCenter[pos][1] - 20,
                            0,1,0
                    );
                }else{
                    GraphicUtils.drawString(
                            "guesser",
                            (int) playerFrameCenter[pos][0],
                            windowHeight - (int) playerFrameCenter[pos][1] - 20,
                            1,0,0
                    );
                }
            }


        }
    }

    private void renderReadyButton(){
        double startX = playerFrameCenter[playerFrameCenter.length - 1][0] + playerFrameSideWidth / 2;
        double endX = windowWidth - gameFrameOffset;
        //startX += 20;
        //endX -= 20;
        double centerX = (startX + endX) / 2;
        double centerY = playerFrameCenter[playerFrameCenter.length - 1][1];
        renderRecLineLoop(centerX, centerY, (endX - startX)/2 - 40, playerFrameSideWidth / 2 - 20);


        if(gameState.getPlayerMyself() == null){
            GraphicUtils.drawString("Connecting", (int) centerX, windowHeight - (int) centerY, 0,1,0);
            return;
        }

        if(gameState.getPlayerMyself().isReady()){
            GraphicUtils.drawString("Cancel", (int) centerX, windowHeight - (int) centerY, 1,0,0);
        }else{
            GraphicUtils.drawString("Ready", (int) centerX, windowHeight - (int) centerY, 0,1,0);
        }
    }

    private void renderGameHelper(){
        double startX = playerFrameCenter[playerFrameCenter.length - 1][0] + playerFrameSideWidth / 2;
        double endX = windowWidth - gameFrameOffset;
        //startX += 20;
        //endX -= 20;
        double centerX = (startX + endX) / 2;
        double centerY = playerFrameCenter[playerFrameCenter.length - 1][1];
        //renderRecLineLoop(centerX, centerY, (endX - startX)/2 - 40, playerFrameSideWidth / 2 - 20);

        if(gameState.isDrawer()){
            GraphicUtils.drawString(gameState.getWord(), (int) centerX, windowHeight - (int) centerY, 0,0,0);
        }else{
            GraphicUtils.drawString("Hint", (int) centerX, windowHeight - (int) centerY, 0,1,0);
        }
    }

    public void clearCanvas(){
        drawnPoints.clear();
    }


    public boolean withinDrawableRegion(double x, double y){
        double leftMost = gameFrameOffset;
        double rightMost = windowWidth - gameFrameOffset;
        double upMost = gameFrameOffset;
        double downMost = windowHeight - gameFrameOffset - playerFrameSideWidth;
        if(x <= leftMost || x >= rightMost) return false;
        if(y <= upMost || y >= downMost) return false;
        return true;
    }

    public boolean withinReadyButtonRegion(double x, double y){
        double startX = playerFrameCenter[playerFrameCenter.length - 1][0] + playerFrameSideWidth / 2;
        double endX = windowWidth - gameFrameOffset;
        double centerX = (startX + endX) / 2;
        double centerY = playerFrameCenter[playerFrameCenter.length - 1][1];
        double offsetX = (endX - startX)/2 - 40;
        double offsetY = playerFrameSideWidth / 2 - 20;


        if(x <= centerX - offsetX || x >= centerX + offsetX) return false;
        if(y <= centerY - offsetY || y >= centerY + offsetY) return false;
        return true;
    }

    public static void main(String[] args) {
    }

}