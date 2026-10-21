document.addEventListener('DOMContentLoaded', function() {
    console.log('Launch It Test - JavaScript loaded successfully');
    document.getElementById('status').textContent = 'JavaScript is working!';
});

function handleClick() {
    console.log('Button clicked!');
    document.getElementById('status').textContent = 'Button clicked at ' + new Date().toLocaleTimeString();
    document.getElementById('heading').style.color = '#FFD700';
}
