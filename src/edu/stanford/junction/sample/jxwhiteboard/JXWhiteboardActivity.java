package edu.stanford.junction.sample.jxwhiteboard;

import edu.stanford.junction.sample.jxwhiteboard.intents.WhiteboardIntents;

import edu.stanford.junction.android.AndroidJunctionMaker;
import edu.stanford.junction.Junction;
import edu.stanford.junction.JunctionException;
import edu.stanford.junction.api.activity.ActivityScript;
import edu.stanford.junction.api.activity.JunctionActor;
import edu.stanford.junction.api.activity.JunctionExtra;
import edu.stanford.junction.api.messaging.MessageHeader;
import edu.stanford.junction.provider.xmpp.XMPPSwitchboardConfig;
import edu.stanford.junction.provider.xmpp.ConnectionTimeoutException;
import edu.stanford.junction.props2.Prop;
import edu.stanford.junction.props2.IPropChangeListener;

import android.content.ServiceConnection;
import android.app.Service;
import android.os.IBinder;
import android.app.Activity;
import android.app.NotificationManager;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Context;
import android.content.ComponentName;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;
import android.widget.EditText;
import android.widget.Toast;
import android.widget.ArrayAdapter;
import android.os.Handler;
import android.os.Message;
import android.os.Process;
import android.app.ListActivity;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.content.Intent;
import android.provider.MediaStore;
import android.util.Log;
import android.net.Uri;

import org.json.*;
import java.net.*;
import java.io.*;
import java.util.*;


public class JXWhiteboardActivity extends Activity{

	private WhiteboardProp prop;

    private static final int REQUEST_CODE_PICK_COLOR = 1;
    private static final int REQUEST_CODE_PICK_LINE_WIDTH = 2;
    private static final int REQUEST_CODE_FIND_WHITEBOARDS = 3;


    private static final int ERASE_COLOR = 0xFFFFFF;
    private static final int ERASE_WIDTH = 30;

    private ActivityScript mScript;
    private ArrayAdapter<String> data;
    private JunctionActor mActor;
	private int currentColor = 0x000000;
	private int currentWidth = 3;
	private boolean eraseMode = false;
	private Bitmap mBackgroundImage = null;
	private DrawingPanel panel = null;

	public static final int SET_COLOR = 0;
	public static final int SET_LINE_WIDTH = 1;
	public static final int START_ERASER = 2;
	public static final int STOP_ERASER = 3;
	public static final int CLEAR = 4;
	public static final int EXIT = 5;
	public static final int SHARE_SNAPSHOT = 6;
	public static final int ADVERTISE = 7;
	public static final int JOIN_BY_NAME = 8;
	public static final int FIND_BOARDS = 9;
	public static final String DEFAULT_HOST = "junction://openjunction.org";



	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		panel = new DrawingPanel(this);
		setContentView(panel);
		
		mScript = new ActivityScript();
		mScript.setActivityID("org.openjunction.whiteboard");
		mScript.setFriendlyName("Whiteboard");
		
		JSONObject androidSpec = new JSONObject();
		try {
			androidSpec.put("url", "http://prpl.stanford.edu/android/whiteboard.apk");
			androidSpec.put("package", this.getPackageName());
		} catch (JSONException e) {
			throw new AssertionError("Error building ActivityScript json");
		}
		mScript.addRolePlatform("participant", "android", androidSpec);
		
		Uri sessionUri;
		if (AndroidJunctionMaker.isJoinable(this)) {
			sessionUri = Uri.parse(getIntent().getStringExtra("invitationURI"));
		} else {
			String randomSession = UUID.randomUUID().toString();
			sessionUri = Uri.parse(DEFAULT_HOST + "/" + randomSession );
		}
		initJunction(sessionUri);
		bindLiaisonService();
	}

	class DrawingPanel extends SurfaceView implements SurfaceHolder.Callback {
		private List<Integer> currentPoints = new ArrayList<Integer>();
		private SurfaceHolder _surfaceHolder;

		
		public DrawingPanel(Context context) {
			super(context);
			getHolder().addCallback(this);
		}
		
		@Override
		public boolean onTouchEvent(MotionEvent event) {
			synchronized (getHolder()) {
				if(event.getAction() == MotionEvent.ACTION_DOWN){
					currentPoints.clear();
					currentPoints.add((int)event.getX());
					currentPoints.add((int)event.getY());
					repaint(false);
				}
				else if(event.getAction() == MotionEvent.ACTION_MOVE){
					currentPoints.add((int)event.getX());
					currentPoints.add((int)event.getY());
					repaint(false);
				}
				else if(event.getAction() == MotionEvent.ACTION_UP){
					currentPoints.add((int)event.getX());
					currentPoints.add((int)event.getY());
					if(eraseMode) prop.add(prop.newStroke(ERASE_COLOR, ERASE_WIDTH, currentPoints));
					else prop.add(prop.newStroke(currentColor, currentWidth, currentPoints));
					currentPoints.clear();
					repaint(false);
				}
				return true;
			}
		}

		protected void paintState(Canvas canvas){
			Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
			mPaint.setDither(true);
			mPaint.setStyle(Paint.Style.STROKE);
			mPaint.setStrokeJoin(Paint.Join.ROUND);
			mPaint.setStrokeCap(Paint.Cap.ROUND);

			// clear canvas
			canvas.drawColor(0xFFFFFFFF);

			// paint prop state
			for (JSONObject o : prop.items()) {
				int color = Integer.parseInt(o.optString("color").substring(1), 16);
				mPaint.setColor(0xFF000000 | color);
				mPaint.setStrokeWidth(o.optInt("width"));
				JSONArray points = o.optJSONArray("points");
				int x1, y1, x2, y2;
				if(points.length() >= 4){
					x1 = points.optInt(0);
					y1 = points.optInt(1);
					for(int i = 2; i < points.length(); i += 2) {
						x2 = points.optInt(i);
						y2 = points.optInt(i+1);
						canvas.drawLine(x1,y1,x2,y2, mPaint);
						x1 = x2;
						y1 = y2;
					}
				}
			}

			paintCurrentStroke(canvas);
		}

		protected void paintCurrentStroke(Canvas canvas){
			Paint mPaint = new Paint();
			mPaint.setDither(true);
			mPaint.setStyle(Paint.Style.STROKE);
			mPaint.setStrokeJoin(Paint.Join.ROUND);
			mPaint.setStrokeCap(Paint.Cap.ROUND);

			// Draw stroke-in-progress
			if(eraseMode){
				mPaint.setColor(0xFF000000 | ERASE_COLOR);
				mPaint.setStrokeWidth(ERASE_WIDTH);
			}
			else{
				mPaint.setColor(0xFF000000 | currentColor);
				mPaint.setStrokeWidth(currentWidth);
			}
			if(currentPoints.size() >= 4){
				int x1, y1, x2, y2;
				x1 = currentPoints.get(0);
				y1 = currentPoints.get(1);
				for(int i = 2; i < currentPoints.size(); i += 2) {
					x2 = currentPoints.get(i);
					y2 = currentPoints.get(i+1);
					canvas.drawLine(x1,y1,x2,y2, mPaint);
					x1 = x2;
					y1 = y2;
				}
			}

		}
		
		public void repaint(boolean all) {
			if(_surfaceHolder != null && mBackgroundImage != null){
				Canvas canvas = new Canvas(mBackgroundImage);
				if(all) paintState(canvas);
				else paintCurrentStroke(canvas);
				try {
					synchronized (_surfaceHolder) {
						canvas = _surfaceHolder.lockCanvas(null);
						canvas.drawBitmap(mBackgroundImage, 0, 0, null);
					}
				}
				finally {
					// do this in a finally so that if an exception is thrown
					// during the above, we don't leave the Surface in an
					// inconsistent state
					if (canvas != null) {
						_surfaceHolder.unlockCanvasAndPost(canvas);
					}
				}
			}
		}
		
		public void surfaceChanged(SurfaceHolder holder, int format, int width,int height) { 
			mBackgroundImage = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			repaint(true);
		}
		
		public void surfaceCreated(SurfaceHolder holder) {
			_surfaceHolder = holder;
			repaint(true);
		}
		
		public void surfaceDestroyed(SurfaceHolder holder) {
			_surfaceHolder = null;
		}

	}


	private boolean externalStorageReadableAndWritable(){
		boolean mExternalStorageAvailable = false;
		boolean mExternalStorageWriteable = false;
		String state = Environment.getExternalStorageState();

		if (Environment.MEDIA_MOUNTED.equals(state)) {
			// We can read and write the media
			return true;
		} else if (Environment.MEDIA_MOUNTED_READ_ONLY.equals(state)) {
			// We can only read the media
			return false;
		} else {
			// Something else is wrong. It may be one of many other states, 
			//  but all we need to know is we can neither read nor write
			return false;
		}
	}

	private void shareSnapshot(){
		if(mBackgroundImage != null){
			String filename = "jxwhiteboard_tmp_output.png";
			// If sd card is available, we'll write the full quality image there
			// before sending..
			if(externalStorageReadableAndWritable()){
				Bitmap bitmap = mBackgroundImage;
				File png = new File(Environment.getExternalStorageDirectory(), filename);
				FileOutputStream out = null;
				try {
					out = new FileOutputStream(png);
					bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
					out.flush();
				} catch (Exception e) {
					e.printStackTrace();
				} finally {
					try {
						if (out != null) out.close();
					}
					catch (IOException ignore) {}
				}
				final Intent shareIntent = new Intent(android.content.Intent.ACTION_SEND);
				shareIntent.putExtra(Intent.EXTRA_SUBJECT, "Whiteboard Snapshot");
				shareIntent.putExtra(Intent.EXTRA_STREAM, Uri.fromFile(png));
				shareIntent.setType("image/png");
				startActivity(Intent.createChooser(shareIntent, "Share Snapshot..."));
			}
			// Otherwise, use mediastore, which unfortunately compresses the hell
			// out of the image.
			else{
				String url = MediaStore.Images.Media.insertImage(
					getContentResolver(), mBackgroundImage, filename, null);
				Intent sendIntent = new Intent(Intent.ACTION_SEND);
				sendIntent.putExtra(Intent.EXTRA_SUBJECT, "Whiteboard Snapshot");
				sendIntent.putExtra(Intent.EXTRA_STREAM, Uri.parse(url));
				sendIntent.setType("image/jpg");
				startActivity(Intent.createChooser(sendIntent, "Share Snapshot..."));
			}
		}
	}

	public boolean onCreateOptionsMenu(Menu menu){
		return true;
	}

	public boolean onPreparePanel(int featureId, View view, Menu menu){
		menu.clear();
		if(eraseMode){
			menu.add(0,STOP_ERASER,0, "Stop Erasing");
			menu.add(0,CLEAR,0, "Clear All");
			menu.add(0,SHARE_SNAPSHOT,0, "Take Snapshot");
			menu.add(0,ADVERTISE,0, "Advertise Board");
			menu.add(0,FIND_BOARDS,0, "Find Boards");
			menu.add(0,JOIN_BY_NAME,0, "Join by Session Id");
			menu.add(0,EXIT,0,"Exit");
		}
		else{
			menu.add(0,SET_COLOR,0,"Set Color");
			menu.add(0,SET_LINE_WIDTH,0,"Set Line Width");
			menu.add(0,START_ERASER,0, "Eraser");
			menu.add(0,CLEAR,0, "Clear All");
			menu.add(0,SHARE_SNAPSHOT,0, "Take Snapshot");
			menu.add(0,ADVERTISE,0, "Advertise Board");
			menu.add(0,FIND_BOARDS,0, "Find Boards");
			menu.add(0,JOIN_BY_NAME,0, "Join by Name");
			menu.add(0,EXIT,0,"Exit");
		}
		return true;
	}

	public boolean onOptionsItemSelected (MenuItem item){
		switch (item.getItemId()){
		case SET_COLOR:
			pickColor();
			return true;
		case SET_LINE_WIDTH:
			pickLineWidth();
			return true;
		case START_ERASER:
			eraseMode = true;
			return true;
		case STOP_ERASER:
			eraseMode = false;
			return true;
		case CLEAR:
			prop.clear();
			return true;
		case SHARE_SNAPSHOT:
			shareSnapshot();
			return true;
		case ADVERTISE:
			advertiseBoard();
			return true;
		case FIND_BOARDS:
			findBoards();
			return true;
		case JOIN_BY_NAME:
			joinByName();
			return true;
		case EXIT:
			Process.killProcess(Process.myPid());
		}
		return false;
	}

	private void pickColor(){
		Intent i = new Intent();
		i.setAction(WhiteboardIntents.ACTION_PICK_COLOR);
		i.putExtra(WhiteboardIntents.EXTRA_COLOR, 0xFF000000 | currentColor );
		startActivityForResult(i, REQUEST_CODE_PICK_COLOR);
	}

	private void pickLineWidth(){
		Intent i = new Intent();
		i.setAction(WhiteboardIntents.ACTION_PICK_LINE_WIDTH);
		i.putExtra(WhiteboardIntents.EXTRA_LINE_WIDTH, currentWidth );
		startActivityForResult(i, REQUEST_CODE_PICK_LINE_WIDTH);
	}

	private void findBoards(){
		Intent i = new Intent();
		i.setAction(WhiteboardIntents.ACTION_FIND_WHITEBOARDS);
		i.putExtra(WhiteboardIntents.EXTRA_SESSION_URL, "");
		startActivityForResult(i, REQUEST_CODE_FIND_WHITEBOARDS);
	}

	private void advertiseBoard(){
		if(mBoundService != null && mActor != null){
			final Junction jx = mActor.getJunction();
			if(jx != null){
				AlertDialog.Builder alert = new AlertDialog.Builder(this);
				alert.setTitle(R.string.advertise_dialog_title);  
				alert.setMessage(R.string.advertise_dialog_prompt);
				final EditText input = new EditText(this);  
				input.setText("whiteboard-" + 
							  UUID.randomUUID().toString().substring(10));
				alert.setView(input);
				alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {  
						public void onClick(DialogInterface dialog, int whichButton){  
							String url = jx.getBaseInvitationURI().toString();
							String name = input.getText().toString();
							mBoundService.advertiseActivity(name, url);
							Toast.makeText(JXWhiteboardActivity.this, R.string.advertised, 
										   Toast.LENGTH_SHORT).show();
						}
					});  
				alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {  
						public void onClick(DialogInterface dialog, int whichButton) {}  
					});  
				alert.show();  
				return;
			}
		}
		Toast.makeText(this, R.string.advertise_failed, Toast.LENGTH_SHORT).show();
	}


	private void joinByName(){
		AlertDialog.Builder alert = new AlertDialog.Builder(this);
		alert.setTitle(R.string.join_dialog_title);  
		alert.setMessage(R.string.join_dialog_prompt);
		final EditText input = new EditText(this);  
		alert.setView(input);  
		alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {  
				public void onClick(DialogInterface dialog, int whichButton){  
					String value = input.getText().toString();
					initJunction(Uri.parse(DEFAULT_HOST + "/" + value));
					panel.repaint(true);
				}  
			});  
		alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {  
				public void onClick(DialogInterface dialog, int whichButton) {}  
			});  
		alert.show();  
	}


	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		switch(requestCode) {
		case REQUEST_CODE_PICK_COLOR:
			if(resultCode == RESULT_OK){
				currentColor = data.getIntExtra(
					WhiteboardIntents.EXTRA_COLOR, currentColor);
				// Get rid of alpha information
				currentColor &= 0x00FFFFFF;
			}
			break;
		case REQUEST_CODE_PICK_LINE_WIDTH:
			if(resultCode == RESULT_OK){
				currentWidth = data.getIntExtra(
					WhiteboardIntents.EXTRA_LINE_WIDTH, currentWidth);
			}
			break;
		case REQUEST_CODE_FIND_WHITEBOARDS:
			if(resultCode == RESULT_OK){
				String url = data.getStringExtra(
					WhiteboardIntents.EXTRA_SESSION_URL);
				initJunction(Uri.parse(url));
			}
			break;
		}
	}



	private void initJunction(Uri uri){
		prop = new WhiteboardProp("whiteboard_model");
		prop.addChangeListener(new IPropChangeListener(){
				public String getType(){ return Prop.EVT_CHANGE; }
				public void onChange(Object data){ 
					panel.repaint(true);
				}
			});
		prop.addChangeListener(new IPropChangeListener(){
				public String getType(){ return Prop.EVT_SYNC; }
				public void onChange(Object data){ 
					panel.repaint(true);
				}
			});

		if(mActor != null){
			mActor.leave();
		}

		URI url = null;
		try{
			url = new URI(uri.toString());
		}
		catch(URISyntaxException e){
			Log.e("JXWhiteboardActivity", "Failed to parse uri: " + uri.toString());
			return;
		}

		mActor = new JunctionActor("participant"){
				@Override
				public void onActivityJoin() {
					System.out.println("Joined!");
				}
				@Override
				public void onMessageReceived(MessageHeader header, JSONObject msg) {
					System.out.println("Got msg!");	
				}
				@Override
				public List<JunctionExtra> getInitialExtras() {
					ArrayList<JunctionExtra> extras = new ArrayList<JunctionExtra>();
					extras.add(prop);
					return extras;
				}
			};
		final XMPPSwitchboardConfig sb = new XMPPSwitchboardConfig(uri.getAuthority());
		sb.setConnectionTimeout(10000);
		try{
			AndroidJunctionMaker.getInstance(sb).newJunction(url, mScript, mActor);
		}
		catch(JunctionException e){
			maybeRetryJunction(uri, e);
		}
	}

	private void maybeRetryJunction(final Uri uri, final JunctionException e){
		AlertDialog.Builder alert = new AlertDialog.Builder(this);
		alert.setTitle("Connection Failed");  
		alert.setMessage("Failed to connect to Whiteboard. " + e.getWrappedThrowable().getMessage() + ". Retry connection?");
		alert.setPositiveButton("Ok", new DialogInterface.OnClickListener() {  
				public void onClick(DialogInterface dialog, int whichButton){  
					initJunction(uri);
				}
			});  
		alert.setNegativeButton("Cancel", new DialogInterface.OnClickListener() {  
				public void onClick(DialogInterface dialog, int whichButton) {}
			});  
		alert.show();  
		return;
	}

	private LiaisonService mBoundService;

	private ServiceConnection mConnection = new ServiceConnection() {
			public void onServiceConnected(ComponentName className, IBinder service) {
				mBoundService = ((LiaisonService.LiaisonBinder)service).getService();
				mBoundService.init(Uri.parse(DEFAULT_HOST + "/" + "whiteboard_lobby"));
			}

			public void onServiceDisconnected(ComponentName className){
				mBoundService = null;
			}
		};

	private void bindLiaisonService(){
		// Establish a connection with the service.  We use an explicit
		// class name because we want a specific service implementation that
		// we know will be running in our own process (and thus won't be
		// supporting component replacement by other applications).
		Intent intent = new Intent(this, LiaisonService.class);
		bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
	}


	public void onDestroy(){
		super.onDestroy();
		unbindService(mConnection);
	}


}



