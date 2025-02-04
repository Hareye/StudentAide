package com.group04.studentaide;

import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.gms.common.api.internal.UnregisterListenerMethod;
import com.google.android.gms.tasks.OnCompleteListener;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.QuerySnapshot;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.security.auth.callback.Callback;

/*
    File Name: StudySession.java
    Team: ProjectTeam04
    Written By: Yufeng Luo, Jason Leung

    Description:
        This class implements the STUDY page inside StudentAide. This class will grab data from the current users courses and current planned study sessions and
        display them in Spinners on this page for the user to select from. It also grabs data from the Timer class and sets data in the Timer class in order to
        implement a functional timer. If the user selects a course to study for right now, the user can input a custom time. If the user selects a planned study
        session, the timer is automatically set to the duration the user has specified when setting up a start date/time and end date/time when planning a session.
        The course will also be automatically set to the course that was specified by the user in the planned study session when creating one. If the user has
        selected a planned study session, selecting off of the auto-selected course will set the planned study session to none selected. When the timer has started,
        if the user pauses the timer or the timer finishes running, it will update the users stats in Cloud Firestore.

    Changes:
        November 15th - Draft 1 of Version 1/2
        November 16th - Draft 2 of Version 1/2
        November 17th - Draft 3 of Version 1/2
        November 18th - Draft 4 of Version 1/2
        November 20th - Finalized Version 1/2
        December 2nd - Draft 1 of Version 3

    Bugs:
        None atm.

 */

/*  To-Do List (For V3):
        Need a way to remove planned study session once the date has passed - Write a function that updates and removes study sessions that have passed

        Check for app in foreground to add to timeStudied or timeDistracted

        Check if whether time counts to studied or distracted when phone is turned off

 */

public class StudySession extends AppCompatActivity implements SensorEventListener {

    Spinner selectSession;
    Spinner courseSpinner;
    Button planSession;

    EditText userInputTime;
    Button setTime;
    Button startTime;
    Button pauseTime;
    Button resetTime;
    TextView textCountdownTimer;

    private SensorManager sensorManager;
    private Sensor accel;

    private CountDownTimer mCountDownTimer;
    private Boolean mTimerRunning = false;
    private long mStartTimeMilli = 0;
    private long mTimeLeftMilli;
    private long mEndTimeMilli;
    private long mEndTime;

    private int counter = 0;

    FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
    FirebaseFirestore db = FirebaseFirestore.getInstance();
    InformationRetrieval infoRetrieve = InformationRetrieval.getInstance();
    Timer timer = Timer.getInstance(this);

    // Needs to be after Timer.getInstance since it will be grabbing the instance of Timer (which should not be null)
    ForegroundCheck foreground = ForegroundCheck.getInstance();

    // Used to fill Spinner
    ArrayList<String> courses = new ArrayList<String>();
    ArrayList<String> sessions = new ArrayList<String>();

    // Used for storing and grabbing from database
    ArrayList<String> documentId = new ArrayList<String>();
    ArrayList<String> courseName = new ArrayList<String>();
    ArrayList<Double> duration = new ArrayList<Double>();
    ArrayList<Double> distracted = new ArrayList<Double>();

    // Used for updating course stats
    boolean updateRequired = false;

    DocumentReference studentRef;
    String studentDocumentId;
    boolean exists = false;
    boolean setCourse = false;
    boolean isDistracted = false;

    ArrayAdapter<String> courseAdapter;
    String sessionCourse;
    Date startDate;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_study_session);

        if (user == null) {
            Toast.makeText(getApplicationContext(), "Please sign in.", Toast.LENGTH_SHORT).show();
            Intent main = new Intent(this, MainActivity.class);
            startActivity(main);
        } else {

            grabDocumentReference();
            courses.clear();
            sessions.clear();

            sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);

            courseSpinner = findViewById(R.id.courses);
            selectSession = findViewById(R.id.selectSession);
            planSession = findViewById(R.id.planSession);
            userInputTime = findViewById(R.id.timeInput);
            pauseTime = findViewById(R.id.pauseTime);
            setTime = findViewById(R.id.setTime);
            textCountdownTimer = findViewById(R.id.timeLeft);
            startTime = findViewById(R.id.startTime);

            BottomNavigationView bottomNav = findViewById(R.id.bottom_navigation);
            bottomNav.setOnNavigationItemSelectedListener(navListener);
            //resetTime = findViewById(R.id.resetTimer);

            // Add a none selected value to the array that will be used to populate courseSpinner and selectSession
            if (counter == 0) {
                sessions.add("No Session Selected");
                courses.add("No Course Selected");
            }

            // Populate courseSpinner with users courses and updateStats once courses have been grabbed
            grabCourses(new Callback() {
                @Override
                public void call() {
                    courseAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_spinner_dropdown_item, courses);
                    courseSpinner.setAdapter(courseAdapter);
                    updateStats();
                    setCourseSpinner();
                }
            });

            // When user has selected a planned study session, selecting off of the auto-selected course will set planned session
            // to No Session Selected and will reset timer to 0
            courseSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    String choice = parent.getItemAtPosition(position).toString();
                    timer.setCourse(choice);
                    if (setCourse == true) {
                        if (!choice.equals(sessionCourse)) {
                            timer.setTimer(0);
                            startDate = null;
                            selectSession.setSelection(0);
                            setCourse = false;
                        }
                    }

                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });

            // Populate selectSession Spinner with "No Planned Session"
            String[] ifNoSessions = new String[]{"No Planned Sessions"};
            ArrayAdapter<String> sessionsAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_spinner_dropdown_item, ifNoSessions);
            selectSession.setAdapter(sessionsAdapter);

            // If the user has planned study sessions, overwrite selectSession Spinner with planned study sessions
            grabStudySession(new Callback() {
                @Override
                public void call() {
                    ArrayAdapter<String> sessionsAdapter = new ArrayAdapter<String>(getActivity(), android.R.layout.simple_spinner_dropdown_item, sessions);
                    selectSession.setAdapter(sessionsAdapter);
                }
            });

            // When user selects a planned study session from selectSession Spinner, change courseSpinner to the planned course
            selectSession.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    String choice = parent.getItemAtPosition(position).toString();

                    // If Spinner gets put on "No Session Selected", reset timer to 0
                    if (choice == "No Session Selected" || choice == "No Planned Sessions") {
                        if (choice == "No Session Selected") {
                            //setTimer(0);
                        }
                    } else {
                        // Set courseSpinner to be the course the planned study session was for
                        getSessionCourse(new Callback() {
                            @Override
                            public void call() {
                                int sessionPosition = courseAdapter.getPosition(sessionCourse);
                                courseSpinner.setSelection(sessionPosition);
                                sessionDuration(choice);
                                setCourse = true;
                            }
                        });
                    }

                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) {

                }
            });

            // If the user does not currently have a document in Firebase for their stats, create one
            if (counter == 0) {
                createStats();
                counter++;
            }

            pauseTime.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    mTimerRunning = timer.getRunning();
                    mStartTimeMilli = timer.getStartTime();

                    // If the timer is currently at 0 seconds and timer is not running, nothing happens
                    if (mStartTimeMilli == 0 || mTimerRunning == false) {

                        Toast.makeText(getApplicationContext(), "Timer is already paused.", Toast.LENGTH_SHORT).show();

                    } else if (mTimerRunning == true) {

                        // If timer is running

                        Log.v("Hareye", "Accelerometer and ForegroundCheck disabled.");
                        sensorManager.unregisterListener(StudySession.this, accel);
                        foreground.stopForegroundCheck();

                        // Grabs the users current stats before storing and updating database with new stats
                        grabStats(new Callback() {
                            @Override
                            public void call() {
                                storeStats(timer.timeStudied, timer.timeDistracted, new Callback() {
                                    @Override
                                    public void call() {
                                        timer.timeStudied = 0;
                                        timer.timeDistracted = 0;
                                    }
                                });
                            }
                        });

                        timer.pauseTimer();

                    }

                }
            });

            // Start the timer
            startTime.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    mTimerRunning = timer.getRunning();

                    if (mTimerRunning == true) {
                        Toast.makeText(getApplicationContext(), "Timer is already running.", Toast.LENGTH_SHORT).show();
                    } else {
                        if (sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null) {
                            Log.v("Hareye", "Accelerometer and ForegroundCheck initialized.");
                            accel = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
                            foreground.startForegroundCheck();
                            sensorManager.registerListener(StudySession.this, accel, SensorManager.SENSOR_DELAY_NORMAL);
                        } else {
                            Toast.makeText(getApplicationContext(), "No accelerometer detected.", Toast.LENGTH_SHORT).show();
                        }

                        LocalDateTime today = LocalDateTime.now();
                        ZoneId zoneId = ZoneId.systemDefault();
                        Date currentDate = Date.from(today.atZone(zoneId).toInstant());

                        // startDate = null means not starting a planned study session, therefore start timer
                        if (startDate == null) {

                            mStartTimeMilli = timer.getStartTime();

                            // If timer is currently at 0 seconds, alert user to set a time
                            if (mStartTimeMilli == 0) {
                                Toast.makeText(getApplicationContext(), "Please set a time.", Toast.LENGTH_SHORT).show();
                            } else {
                                mStartTimeMilli = mTimeLeftMilli;
                                timer.startTimer();
                            }

                        } else {

                            String selectedSession = selectSession.getSelectedItem().toString();

                            // If startDate is before currentDate, do not start timer
                            if (startDate.after(currentDate) && !selectedSession.equals("No Session Selected")) {
                                Toast.makeText(getApplicationContext(), "It is not time yet.", Toast.LENGTH_SHORT).show();
                            } else {

                                mStartTimeMilli = timer.getStartTime();

                                // If timer is currently at 0 seconds, alert user to set a time
                                if (mStartTimeMilli == 0) {
                                    Toast.makeText(getApplicationContext(), "Please set a time.", Toast.LENGTH_SHORT).show();
                                } else {
                                    mStartTimeMilli = mTimeLeftMilli;
                                    timer.startTimer();
                                }

                            }

                        }
                    }

                }
            });

            // Takes user to the studySessionPlan page
            planSession.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Not sure if it should be "getApplicationContext()", or "this"
                    Intent intent = new Intent(getApplicationContext(), StudySessionPlan.class);
                    startActivity(intent);
                }
            });

            // Set the timer
            setTime.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {

                    String timeInput = userInputTime.getText().toString().trim();
                    long millisLeftToTime = 0;

                    // Check if user has entered a valid number in userInputTime
                    try {
                        millisLeftToTime = Long.parseLong(timeInput) * 60000;
                    } catch (NumberFormatException nfe) {
                        Toast.makeText(getApplicationContext(), "Please enter a valid time.", Toast.LENGTH_SHORT).show();
                    }

                    // If no course is selected or timer is at 0 seconds, alert user
                    if (courseSpinner.getSelectedItem() == "No Course Selected" || millisLeftToTime == 0) {

                        if (millisLeftToTime == 0) {
                            Toast.makeText(getApplicationContext(), "Please enter a time.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getApplicationContext(), "Please select a course.", Toast.LENGTH_SHORT).show();
                        }

                    } else {

                        // Set the timer
                        timer.setTimer(millisLeftToTime);
                        userInputTime.setText("");

                    }

                }
            });

            /*resetTime.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    resetTimer();
                }
            });*/

        }

    }

    @Override
    protected void onResume() {

        super.onResume();

        timer.setInstance(this);

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {



    }

    @Override
    public void onSensorChanged(SensorEvent event) {

        float accelX = event.values[0];
        float accelY = event.values[1];
        float accelZ = event.values[2];

        timer.setAccelX(accelX);
        timer.setAccelY(accelY);
        timer.setAccelZ(accelZ);

    }

    public void finishTimer() {

        Log.v("Hareye", "Accelerometer and ForegroundCheck disabled.");
        sensorManager.unregisterListener(StudySession.this, accel);
        foreground.stopForegroundCheck();

    }

    private BottomNavigationView.OnNavigationItemSelectedListener navListener =
            new BottomNavigationView.OnNavigationItemSelectedListener() {
                @Override
                public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                    switch(item.getItemId()){
                        case R.id.nav_study:
                            Intent study = new Intent(StudySession.this, StudySession.class);
                            startActivity(study);
                            break;
                        case R.id.nav_courses:
                            Intent courses = new Intent(StudySession.this, CoursesActivity.class);
                            startActivity(courses);
                            break;
                        case R.id.nav_home:
                            Intent main = new Intent(StudySession.this, MainActivity.class);
                            startActivity(main);
                    }
                    return true;
                }
            };

    // Return current activity
    private StudySession getActivity() {

        return this;

    }

    // Return current users document ID and document reference path
    public void grabDocumentReference() {

        studentDocumentId = infoRetrieve.getDocumentID();
        studentRef = db.collection("Students").document(studentDocumentId);

    }

    public void setCourseSpinner() {

        String course = timer.getCourse();

        if (course == null) {
            // Nothing happens
        } else {
            int coursePosition = courseAdapter.getPosition(course);
            courseSpinner.setSelection(coursePosition);
        }

    }

    private void hideKeyboard(){
        View view = this.getCurrentFocus();
        InputMethodManager input = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        input.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    // Grabs users current stats from the database
    public void grabStats(Callback callback) {

        courseName.clear();
        duration.clear();
        distracted.clear();
        documentId.clear();

        db.collection("Statistics")
                .whereEqualTo("Student_SA_ID", studentRef)
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                Map<String, Double> coursesTimeStudied = (Map<String, Double>) document.get("coursesTimeStudied");
                                Map<String, Double> timeDistracted = (Map<String, Double>) document.get("timeDistracted");

                                for (Map.Entry<String, Double> entry : coursesTimeStudied.entrySet()) {

                                    String k = entry.getKey();
                                    Double v = (double) entry.getValue();

                                    courseName.add(k);
                                    duration.add(v);

                                }

                                for (Map.Entry<String, Double> entry : timeDistracted.entrySet()) {

                                    Double v = (double) entry.getValue();

                                    distracted.add(v);

                                }

                                documentId.add(document.getId());

                                callback.call();
                            }
                        } else {
                            Log.v("StudySession", "Error occurred when getting data from Firebase.");
                        }
                    }
                });

    }

    // Grab the course from the current selected planned study session
    public void getSessionCourse(Callback callback) {

        String session = selectSession.getSelectedItem().toString();

        db.collection("PlannedSessions")
                .whereEqualTo("Student_SA_ID", studentRef)
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                Timestamp start = (Timestamp) document.get("Start");
                                Date date = start.toDate();
                                String dateString = date.toString();

                                String currentCourse = (String) document.get("Course_Name");

                                if (session.equals(dateString)) {

                                    startDate = date;
                                    sessionCourse = currentCourse;

                                }

                            }
                            callback.call();
                        } else {
                            Log.v("StudySession", "Error occurred when getting data from Firebase.");
                        }
                    }
                });

    }

    public void finishStore() {

        // Grabs the users current stats before storing and updating database with new stats
        grabStats(new Callback() {
            @Override
            public void call() {
                storeStats(timer.timeStudied, timer.timeDistracted, new Callback() {
                    @Override
                    public void call() {
                        timer.timeStudied = 0;
                        timer.timeDistracted = 0;
                    }
                });
            }
        });

    }

    // Stores and updates user stats in database if it exists
    public void storeStats(double timeStudied, double timeDistracted, Callback callback) {

        String currentCourse = timer.getCourse();

        if (documentId.isEmpty() == false) {

            double totalTimeStudied = 0;
            double totalTimeDistracted = 0;

            Map<String, Double> courseStats = new HashMap<>();
            Map<String, Double> distractedStats = new HashMap<>();
            for (int i = 0; i < courseName.size(); i++) {
                if (courseName.get(i).equals(currentCourse)) {
                    double newDuration = Math.round(duration.get(i) + timeStudied);
                    double newDistracted = Math.round(distracted.get(i) + timeDistracted);
                    courseStats.put(courseName.get(i), newDuration);
                    distractedStats.put(courseName.get(i), newDistracted);
                } else {
                    courseStats.put(courseName.get(i), duration.get(i));
                    distractedStats.put(courseName.get(i), distracted.get(i));
                }
            }

            for (int i = 0; i < duration.size(); i++) {
                totalTimeStudied += Math.round(duration.get(i));
            }
            for (int i = 0; i < distracted.size(); i++) {
                totalTimeDistracted += Math.round(distracted.get(i));
            }

            totalTimeStudied += Math.round(timeStudied);
            totalTimeDistracted += Math.round(timeDistracted);

            DocumentReference userRef = db.collection("Statistics").document(documentId.get(0));
            userRef
                    .update("totalTimeStudied", totalTimeStudied, "totalTimeDistracted", totalTimeDistracted,
                            "coursesTimeStudied", courseStats, "timeDistracted", distractedStats)
                    .addOnSuccessListener(new OnSuccessListener<Void>() {
                        @Override
                        public void onSuccess(Void aVoid) {
                            Log.v("StudySession", "Updated totalTimeStudied and coursesTimeStudied field");
                            callback.call();
                        }
                    })
                    .addOnFailureListener(new OnFailureListener() {
                        @Override
                        public void onFailure(@NonNull Exception e) {
                            Log.v("StudySession", "Error updating document", e);
                            callback.call();
                        }
                    });

        }

    }

    // Updates users stats if user has enrolled in new courses
    public void updateStats() {

        grabStats(new Callback() {
            @Override
            public void call() {

                Map<String, Double> courseStats = new HashMap<>();
                Map<String, Double> distractedStats = new HashMap<>();

                for (int i = 1; i < courses.size(); i++) {
                    if (courseName.size() == 0) {
                        courseStats.put(courses.get(i), 0.0);
                        distractedStats.put(courses.get(i), 0.0);
                        updateRequired = true;
                    }
                    for (int j = 0; j < courseName.size(); j++) {
                        if (!courseName.contains(courses.get(i))) {
                            courseStats.put(courses.get(i), 0.0);
                            distractedStats.put(courses.get(i), 0.0);
                            updateRequired = true;
                        } else if (courseName.get(j).equals(courses.get(i))){
                            courseStats.put(courses.get(i), duration.get(j));
                            distractedStats.put(courses.get(i), distracted.get(j));
                        }
                    }
                }

                if (updateRequired) {

                    DocumentReference statsRef = db.collection("Statistics").document(documentId.get(0));
                    statsRef
                            .update("coursesTimeStudied", courseStats, "timeDistracted", distractedStats)
                            .addOnSuccessListener(new OnSuccessListener<Void>() {
                                @Override
                                public void onSuccess(Void aVoid) {
                                    Log.v("StudySession", "Updated coursesTimeStudied and timeDistracted field");
                                }
                            })
                            .addOnFailureListener(new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull Exception e) {
                                    Log.v("StudySession", "Error updating document", e);
                                }
                            });

                }

            }
        });

    }

    // If user does not have a document for their stats, create one
    public void createStats() {

        statExists(new Callback() {
            @Override
            public void call() {

                if (exists == false) {

                    // If the users stats don't exist, then create one
                    Map<String, Double> courseStats = new HashMap<>();
                    Map<String, Double> distractedStats = new HashMap<>();
                    for (int i = 1; i < courses.size(); i++) {
                        courseStats.put(courses.get(i), 0.0);
                        distractedStats.put(courses.get(i), 0.0);
                    }

                    Map<String, Object> stats = new HashMap<>();
                    stats.put("Student_SA_ID", studentRef);
                    stats.put("totalTimeStudied", 0.0);
                    stats.put("totalTimeDistracted", 0.0);
                    stats.put("timeDistracted", distractedStats);
                    stats.put("coursesTimeStudied", courseStats);

                    db.collection("Statistics")
                            .add(stats)
                            .addOnSuccessListener(new OnSuccessListener<DocumentReference>() {
                                @Override
                                public void onSuccess(DocumentReference documentReference) {
                                    Log.v("StudySession", "Document added with ID: " + documentReference.getId());
                                }
                            })
                            .addOnFailureListener(new OnFailureListener() {
                                @Override
                                public void onFailure(@NonNull Exception e) {
                                    Log.v("StudySession", "Error adding document");
                                }
                            });

                }

            }
        });

    }

    // Checks if the users stats currently exist
    public void statExists(Callback callback) {

        db.collection("Statistics")
                .whereEqualTo("Student_SA_ID", studentRef)
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                exists = true;
                                callback.call();
                            }
                            callback.call();
                        } else {

                        }
                    }
                });

    }

    // Grabs all courses that the user is enrolled in to display in Spinner
    public void grabCourses(Callback callback) {

        db.collection("StudentCourses")
                .whereEqualTo("Student_SA_ID", studentRef)
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                String courseName = (String) document.get("CourseName");

                                courses.add(courseName);
                            }
                            callback.call();
                        } else {
                            Log.v("StudySession", "Error occurred when getting data from Firebase.");
                        }
                    }
                });

    }

    // Grabs all planned study sessions that the user has to display in Spinner
    public void grabStudySession(Callback callback) {

        db.collection("PlannedSessions")
                .whereEqualTo("Student_SA_ID", studentRef)
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {

                            // Store the Date of the start Timestamp into sessions spinner to be displayed
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                Timestamp start = (Timestamp) document.get("Start");
                                Date date = start.toDate();

                                sessions.add(String.valueOf(date));
                                callback.call();
                            }

                        } else {
                            Log.v("StudySession", "Error occurred when getting data from database.");
                        }
                    }
                });

    }

    // When user selects a planned study session from the spinner, update timer to show the duration
    public void sessionDuration(String choice) {

        db.collection("PlannedSessions")
                .whereEqualTo("Student_SA_ID", studentRef)
                .get()
                .addOnCompleteListener(new OnCompleteListener<QuerySnapshot>() {
                    @Override
                    public void onComplete(@NonNull Task<QuerySnapshot> task) {
                        if (task.isSuccessful()) {
                            for (QueryDocumentSnapshot document : task.getResult()) {
                                Timestamp start = (Timestamp) document.get("Start");
                                Timestamp end = (Timestamp) document.get("End");

                                Date startDate = start.toDate();
                                Date endDate = end.toDate();

                                String startString = String.valueOf(startDate);

                                if (startString.equals(choice)) {

                                    long startMillis = startDate.getTime();
                                    long endMillis = endDate.getTime();
                                    long diffMillis = endMillis - startMillis;

                                    timer.setTimer(diffMillis);

                                }
                            }
                        }
                    }
                });

    }

    // Callback function
    public interface Callback {
        void call();
    }

}